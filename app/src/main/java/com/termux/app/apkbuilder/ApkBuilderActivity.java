package com.termux.app.apkbuilder;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
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
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * TermuxMod: native front-end for the user's own APK-builder shell script
 * (V2 — fully headless, no visible terminal for any of this).
 *
 * All actions (build, import backup/NDK) run via {@link ApkBuilderRunner}
 * (background {@code AppShell}, not a terminal session) and stream their
 * output live into {@link ApkBuilderLogActivity}. The script itself is never
 * modified — this only automates the same keystrokes ("1", "y", Enter, etc.)
 * a person would type at its interactive menu, see
 * {@link ApkBuilderRunner.StdinScripts}.
 *
 * IMPORTANT — these stdin keystroke sequences and the "import zip" file
 * naming/location are matched to this specific script's menu layout and
 * import_backup() logic (main menu: 1=Debug, 2=Release, 3=Auto-Setup,
 * 5=Import Backup, 6=Export Backup; import expects
 * /sdcard/builder-backup-complete-*.zip). If the script changes its menu
 * numbers or file-naming convention, these constants need to be updated to
 * match — they are not derived automatically.
 */
public class ApkBuilderActivity extends AppCompatActivity {

    // TermuxMod: matches the state file convention used by the builder script
    // (APP_STATE_DIR="$HOME/.termux-apk-builder", LAST_PROJECT_FILE="$APP_STATE_DIR/last_project.txt").
    private static final String STATE_DIR_NAME = ".termux-apk-builder";
    private static final String LAST_PROJECT_FILE_NAME = "last_project.txt";

    // TermuxMod: matches import_backup()'s scan pattern
    // ("ls -t /sdcard/builder-backup-complete-*.zip") in the builder script.
    private static final String BACKUP_ZIP_PREFIX = "builder-backup-complete-";

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

        findViewById(R.id.apk_builder_import_zip).setOnClickListener(v -> {
            Intent intent = new Intent(this, FileBrowserActivity.class);
            intent.putExtra(FileBrowserActivity.EXTRA_PICK_MODE, FileBrowserActivity.PICK_MODE_FILE);
            mPickZipLauncher.launch(intent);
        });

        findViewById(R.id.apk_builder_auto_setup).setOnClickListener(v -> runHeadless(
            getString(R.string.apk_builder_auto_setup_title), ApkBuilderRunner.StdinScripts.AUTO_SETUP));

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
        findViewById(R.id.apk_builder_auto_setup).setEnabled(mScriptPath != null);
        findViewById(R.id.apk_builder_import_zip).setEnabled(mScriptPath != null);
    }

    /**
     * Writes the picked project path into the script's own "last project" state
     * file, then runs it headlessly with the Debug/Release menu keystrokes.
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

        String stdin = "debug".equals(buildType)
            ? ApkBuilderRunner.StdinScripts.BUILD_DEBUG
            : ApkBuilderRunner.StdinScripts.BUILD_RELEASE;
        String title = "debug".equals(buildType)
            ? getString(R.string.apk_builder_log_title_debug)
            : getString(R.string.apk_builder_log_title_release);
        runHeadless(title, stdin);
    }

    /** Copies the picked zip to where import_backup() expects it, then asks to run import now. */
    private void onZipPicked(File pickedZip) {
        File sdcard = Environment.getExternalStorageDirectory();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new java.util.Date());
        File destZip = new File(sdcard, BACKUP_ZIP_PREFIX + stamp + ".zip");

        try (FileInputStream in = new FileInputStream(pickedZip);
             FileOutputStream out = new FileOutputStream(destZip)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.apk_builder_import_copy_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.apk_builder_import_ready_title)
            .setMessage(getString(R.string.apk_builder_import_ready_message, destZip.getName()))
            .setPositiveButton(R.string.apk_builder_import_ready_run, (d, w) ->
                runHeadless(getString(R.string.apk_builder_log_title_import), ApkBuilderRunner.StdinScripts.IMPORT_BACKUP))
            .setNegativeButton(R.string.file_browser_cancel, null)
            .show();
    }

    private void runHeadless(String title, String stdinScript) {
        if (mScriptPath == null) return;
        Intent intent = new Intent(this, ApkBuilderLogActivity.class);
        intent.putExtra(ApkBuilderLogActivity.EXTRA_SCRIPT_PATH, mScriptPath);
        intent.putExtra(ApkBuilderLogActivity.EXTRA_STDIN_SCRIPT, stdinScript);
        intent.putExtra(ApkBuilderLogActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }
}
