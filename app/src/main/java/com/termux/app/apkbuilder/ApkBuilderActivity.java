package com.termux.app.apkbuilder;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.filebrowser.FileBrowserActivity;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * TermuxMod: native front-end for the bundled APK-builder script.
 *
 * V3 change from the previous version: the builder script is no longer
 * something the user has to locate and pick with the file browser — it
 * ships inside the APK itself (assets/apkbuilder/build.sh) and gets copied
 * to a fixed path on every launch of this screen. The user only ever picks
 * their PROJECT folder; the script is an implementation detail they never
 * see or choose.
 *
 * Everything about how the menu keystrokes are automated
 * ({@link ApkBuilderRunner.StdinScripts}) and how output streams live into
 * {@link ApkBuilderLogActivity} is unchanged from before — only the "where
 * does the script come from" part changed here.
 */
public class ApkBuilderActivity extends AppCompatActivity {

    // TermuxMod: path inside the APK's assets/ where the bundled script lives.
    private static final String BUNDLED_SCRIPT_ASSET_PATH = "apkbuilder/build.sh";

    // TermuxMod: matches the state file convention used by the builder script
    // (APP_STATE_DIR="$HOME/.termux-apk-builder", LAST_PROJECT_FILE="$APP_STATE_DIR/last_project.txt").
    // The extracted copy of the bundled script also lives in this same folder.
    private static final String STATE_DIR_NAME = ".termux-apk-builder";
    private static final String LAST_PROJECT_FILE_NAME = "last_project.txt";
    private static final String EXTRACTED_SCRIPT_NAME = "build.sh";

    // TermuxMod: matches import_backup()'s scan pattern
    // ("ls -t /sdcard/builder-backup-complete-*.zip") in the builder script.
    private static final String BACKUP_ZIP_PREFIX = "builder-backup-complete-";

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private TextView mProjectPathText;
    private MaterialButton mBuildDebugButton;
    private MaterialButton mBuildReleaseButton;
    private android.view.View mBusyOverlay;
    private TextView mBusyText;
    private ProgressBar mBusyProgress;

    private String mScriptPath; // path to the extracted (bundled) script; set in onCreate.
    private String mProjectPath;

    private final ActivityResultLauncher<Intent> mPickProjectLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            String path = result.getData().getStringExtra(FileBrowserActivity.RESULT_EXTRA_PATH);
            if (path == null) return;
            mProjectPath = path;
            refreshUi();
        });

    private final ActivityResultLauncher<Intent> mPickZipLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            String path = result.getData().getStringExtra(FileBrowserActivity.RESULT_EXTRA_PATH);
            if (path == null) return;
            onZipPicked(new File(path));
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apk_builder);

        Toolbar toolbar = findViewById(R.id.apk_builder_toolbar);
        setSupportActionBar(toolbar);

        mProjectPathText = findViewById(R.id.apk_builder_project_path);
        mBuildDebugButton = findViewById(R.id.apk_builder_build_debug);
        mBuildReleaseButton = findViewById(R.id.apk_builder_build_release);
        mBusyOverlay = findViewById(R.id.apk_builder_busy_overlay);
        mBusyText = findViewById(R.id.apk_builder_busy_text);
        mBusyProgress = findViewById(R.id.apk_builder_busy_progress);

        findViewById(R.id.apk_builder_pick_project).setOnClickListener(v -> {
            Intent intent = new Intent(this, FileBrowserActivity.class);
            intent.putExtra(FileBrowserActivity.EXTRA_PICK_MODE, FileBrowserActivity.PICK_MODE_FOLDER);
            mPickProjectLauncher.launch(intent);
        });

        findViewById(R.id.apk_builder_import_zip).setOnClickListener(v -> {
            Intent intent = new Intent(this, FileBrowserActivity.class);
            intent.putExtra(FileBrowserActivity.EXTRA_PICK_MODE, FileBrowserActivity.PICK_MODE_FILE);
            mPickZipLauncher.launch(intent);
        });

        findViewById(R.id.apk_builder_auto_setup).setOnClickListener(v -> runHeadless(
            getString(R.string.apk_builder_auto_setup_title), ApkBuilderRunner.StdinScripts.AUTO_SETUP));

        mBuildDebugButton.setOnClickListener(v -> startBuild("debug"));
        mBuildReleaseButton.setOnClickListener(v -> startBuild("release"));

        extractBundledScript();
        refreshUi();
    }

    /**
     * Copies the script bundled in the APK's assets to a fixed path in the
     * Termux home directory, overwriting any previous copy — this always keeps
     * whatever version shipped with this build of the app, so there's nothing
     * for the user to manage. Runs on a background thread: the file is small
     * (tens of KB) so this finishes near-instantly, but any disk I/O on the
     * main thread is worth avoiding on principle after the zip-copy ANR bug.
     */
    private void extractBundledScript() {
        File stateDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, STATE_DIR_NAME);
        File destScript = new File(stateDir, EXTRACTED_SCRIPT_NAME);
        mScriptPath = destScript.getAbsolutePath();

        new Thread(() -> {
            try {
                if (!stateDir.exists() && !stateDir.mkdirs()) {
                    throw new IOException("mkdir failed: " + stateDir);
                }
                try (InputStream in = getAssets().open(BUNDLED_SCRIPT_ASSET_PATH);
                     FileOutputStream out = new FileOutputStream(destScript)) {
                    copyStream(in, out);
                }
            } catch (IOException e) {
                mMainHandler.post(() -> Toast.makeText(this,
                    getString(R.string.apk_builder_script_extract_failed, e.getMessage()), Toast.LENGTH_LONG).show());
            }
        }, "ApkBuilderScriptExtract").start();
    }

    private void refreshUi() {
        mProjectPathText.setText(mProjectPath != null ? mProjectPath : getString(R.string.apk_builder_not_selected));

        boolean canBuild = mProjectPath != null;
        mBuildDebugButton.setEnabled(canBuild);
        mBuildReleaseButton.setEnabled(canBuild);
    }

    /**
     * Writes the picked project path into the script's own "last project" state
     * file, then runs it headlessly with the Debug/Release menu keystrokes.
     */
    private void startBuild(String buildType) {
        if (mProjectPath == null) return;

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

        String stdin = "debug".equals(buildType)
            ? ApkBuilderRunner.StdinScripts.BUILD_DEBUG
            : ApkBuilderRunner.StdinScripts.BUILD_RELEASE;
        String title = "debug".equals(buildType)
            ? getString(R.string.apk_builder_log_title_debug)
            : getString(R.string.apk_builder_log_title_release);
        runHeadless(title, stdin);
    }

    /**
     * Copies the picked zip to where import_backup() expects it, then asks to
     * run import now. Runs on a background thread with a busy overlay — the
     * previous version did this on the main thread and caused an ANR
     * ("Termux tidak menanggapi") for large (1GB+) NDK zips.
     */
    private void onZipPicked(File pickedZip) {
        setBusy(true, getString(R.string.apk_builder_copying_zip));

        new Thread(() -> {
            File sdcard = Environment.getExternalStorageDirectory();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new java.util.Date());
            File destZip = new File(sdcard, BACKUP_ZIP_PREFIX + stamp + ".zip");

            try (FileInputStream in = new FileInputStream(pickedZip);
                 FileOutputStream out = new FileOutputStream(destZip)) {
                copyStream(in, out);
            } catch (IOException e) {
                mMainHandler.post(() -> {
                    setBusy(false, null);
                    Toast.makeText(this, getString(R.string.apk_builder_import_copy_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                });
                return;
            }

            mMainHandler.post(() -> {
                setBusy(false, null);
                new AlertDialog.Builder(this)
                    .setTitle(R.string.apk_builder_import_ready_title)
                    .setMessage(getString(R.string.apk_builder_import_ready_message, destZip.getName()))
                    .setPositiveButton(R.string.apk_builder_import_ready_run, (d, w) ->
                        runHeadless(getString(R.string.apk_builder_log_title_import), ApkBuilderRunner.StdinScripts.IMPORT_BACKUP))
                    .setNegativeButton(R.string.file_browser_cancel, null)
                    .show();
            });
        }, "ApkBuilderZipCopy").start();
    }

    private void setBusy(boolean busy, String message) {
        mBusyOverlay.setVisibility(busy ? android.view.View.VISIBLE : android.view.View.GONE);
        if (busy) mBusyText.setText(message);
        // Disable actions while a copy is in progress so the user can't start two
        // things at once against the same files.
        findViewById(R.id.apk_builder_pick_project).setEnabled(!busy);
        findViewById(R.id.apk_builder_import_zip).setEnabled(!busy);
        findViewById(R.id.apk_builder_auto_setup).setEnabled(!busy);
        mBuildDebugButton.setEnabled(!busy && mProjectPath != null);
        mBuildReleaseButton.setEnabled(!busy && mProjectPath != null);
    }

    private static void copyStream(InputStream in, FileOutputStream out) throws IOException {
        byte[] buffer = new byte[256 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private void runHeadless(String title, String stdinScript) {
        Intent intent = new Intent(this, ApkBuilderLogActivity.class);
        intent.putExtra(ApkBuilderLogActivity.EXTRA_SCRIPT_PATH, mScriptPath);
        intent.putExtra(ApkBuilderLogActivity.EXTRA_STDIN_SCRIPT, stdinScript);
        intent.putExtra(ApkBuilderLogActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }
}
