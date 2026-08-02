package com.termux.app.filebrowser;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TermuxMod: simple file browser for the sdcard and the Termux home directory.
 * Tap a directory to enter it, tap a ".sh" file to run it in a new Termux
 * session, tap any other file to open it with a system app.
 */
/**
 * TermuxMod: simple file browser for the sdcard and the Termux home directory.
 *
 * Default mode: tap a directory to enter it, tap a ".sh" file to run it in a
 * new Termux session, tap any other file to open it with a system app.
 *
 * Picker modes (used by other screens, e.g. the APK Builder):
 * - {@link #EXTRA_PICK_MODE} = {@link #PICK_MODE_FOLDER}: shows a "Pilih folder
 *   ini" button; tapping it returns the current directory via setResult().
 * - {@link #EXTRA_PICK_MODE} = {@link #PICK_MODE_FILE}: tapping any file
 *   returns its path via setResult() instead of running/opening it.
 */
public class FileBrowserActivity extends AppCompatActivity implements FileBrowserAdapter.OnEntryClickListener {

    private static final String SDCARD_ROOT_TAG = "sdcard";
    private static final String HOME_ROOT_TAG = "home";

    public static final String EXTRA_PICK_MODE = "com.termux.app.filebrowser.EXTRA_PICK_MODE";
    public static final String PICK_MODE_FOLDER = "folder";
    public static final String PICK_MODE_FILE = "file";
    /** Result extra holding the picked absolute path, set on {@link #RESULT_OK}. */
    public static final String RESULT_EXTRA_PATH = "com.termux.app.filebrowser.RESULT_EXTRA_PATH";

    /** Optional extra: absolute path to start browsing from, instead of the sdcard root. */
    public static final String EXTRA_START_PATH = "com.termux.app.filebrowser.EXTRA_START_PATH";

    private String mPickMode; // null = normal browse mode

    private TextView mPathText;
    private TextView mEmptyText;
    private RecyclerView mListView;
    private FileBrowserAdapter mAdapter;
    private MaterialButton mPickFolderButton;

    private File mRootDir;
    private File mCurrentDir;
    private String mCurrentRootTag = SDCARD_ROOT_TAG;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        mPickMode = getIntent().getStringExtra(EXTRA_PICK_MODE);

        Toolbar toolbar = findViewById(R.id.file_browser_toolbar);
        setSupportActionBar(toolbar);
        if (PICK_MODE_FOLDER.equals(mPickMode)) {
            toolbar.setTitle(R.string.file_browser_pick_folder_title);
        } else if (PICK_MODE_FILE.equals(mPickMode)) {
            toolbar.setTitle(R.string.file_browser_pick_file_title);
        }

        mPathText = findViewById(R.id.file_browser_path);
        mEmptyText = findViewById(R.id.file_browser_empty_text);
        mListView = findViewById(R.id.file_browser_list);
        mListView.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new FileBrowserAdapter(this);
        mListView.setAdapter(mAdapter);

        ImageButton upButton = findViewById(R.id.file_browser_up_button);
        upButton.setOnClickListener(v -> navigateUp());

        MaterialButton sdcardButton = findViewById(R.id.file_browser_switch_sdcard);
        sdcardButton.setOnClickListener(v -> switchRoot(SDCARD_ROOT_TAG));

        MaterialButton homeButton = findViewById(R.id.file_browser_switch_home);
        homeButton.setOnClickListener(v -> switchRoot(HOME_ROOT_TAG));

        mPickFolderButton = findViewById(R.id.file_browser_pick_folder_button);
        if (PICK_MODE_FOLDER.equals(mPickMode)) {
            mPickFolderButton.setVisibility(View.VISIBLE);
            mPickFolderButton.setOnClickListener(v -> returnPickedPath(mCurrentDir.getAbsolutePath()));
        }

        String startPath = getIntent().getStringExtra(EXTRA_START_PATH);
        if (startPath != null && new File(startPath).isDirectory()) {
            mRootDir = SDCARD_ROOT_TAG.equals(mCurrentRootTag) ? Environment.getExternalStorageDirectory()
                : new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
            mCurrentDir = new File(startPath);
            listCurrentDir();
        } else {
            switchRoot(SDCARD_ROOT_TAG);
        }
    }

    private void returnPickedPath(String path) {
        Intent result = new Intent();
        result.putExtra(RESULT_EXTRA_PATH, path);
        setResult(RESULT_OK, result);
        finish();
    }


    /** Switches between the sdcard root and the Termux app home directory. */
    private void switchRoot(String rootTag) {
        mCurrentRootTag = rootTag;

        if (SDCARD_ROOT_TAG.equals(rootTag)) {
            boolean hasAccess = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                ? Environment.isExternalStorageManager()
                : hasLegacyStoragePermission();
            if (!hasAccess) {
                showStoragePermissionNeeded();
                return;
            }
            mRootDir = Environment.getExternalStorageDirectory();
        } else {
            mRootDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        }

        mCurrentDir = mRootDir;
        listCurrentDir();
    }

    private boolean hasLegacyStoragePermission() {
        return androidx.core.content.ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void showStoragePermissionNeeded() {
        mAdapter.setEntries(new ArrayList<>());
        mPathText.setText(R.string.file_browser_storage_not_granted);
        mEmptyText.setVisibility(View.VISIBLE);
        mEmptyText.setText(R.string.file_browser_storage_not_granted);

        new AlertDialog.Builder(this)
            .setMessage(R.string.file_browser_storage_not_granted)
            .setPositiveButton(R.string.file_browser_open_settings, (dialog, which) -> {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                }
            })
            .setNegativeButton(R.string.file_browser_cancel, null)
            .show();
    }

    private void navigateUp() {
        if (mCurrentDir == null || mRootDir == null) return;
        if (mCurrentDir.equals(mRootDir)) {
            finish();
            return;
        }
        File parent = mCurrentDir.getParentFile();
        mCurrentDir = (parent != null) ? parent : mRootDir;
        listCurrentDir();
    }

    @Override
    public void onBackPressed() {
        if (mCurrentDir != null && mRootDir != null && !mCurrentDir.equals(mRootDir)) {
            navigateUp();
        } else {
            super.onBackPressed();
        }
    }

    private void listCurrentDir() {
        mPathText.setText(mCurrentDir.getAbsolutePath());

        File[] files = mCurrentDir.listFiles();
        List<FileEntry> entries = new ArrayList<>();

        if (files == null) {
            // Not readable (permission denied on this specific subfolder, etc).
            mAdapter.setEntries(entries);
            mEmptyText.setVisibility(View.VISIBLE);
            mEmptyText.setText(R.string.file_browser_permission_denied);
            return;
        }

        for (File f : files) {
            // Skip hidden dotfiles to keep the list clean, same convention as `ls` without `-a`.
            if (f.getName().startsWith(".")) continue;
            entries.add(new FileEntry(f));
        }

        Collections.sort(entries, (a, b) -> {
            if (a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        mAdapter.setEntries(entries);
        mEmptyText.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        mEmptyText.setText(R.string.file_browser_empty_directory);
    }

    @Override
    public void onEntryClick(FileEntry entry) {
        if (entry.isDirectory) {
            mCurrentDir = entry.file;
            listCurrentDir();
        } else if (PICK_MODE_FILE.equals(mPickMode)) {
            returnPickedPath(entry.file.getAbsolutePath());
        } else if (entry.isScript) {
            confirmAndRunScript(entry.file);
        } else {
            openWithSystemApp(entry.file);
        }
    }

    @Override
    public void onEntryLongClick(FileEntry entry) {
        if (!entry.isDirectory) {
            openWithSystemApp(entry.file);
        }
    }

    /** Runs the given ".sh" file via `bash <script>` in a new Termux terminal session. */
    private void confirmAndRunScript(File script) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.file_browser_run_script_title)
            .setMessage(getString(R.string.file_browser_run_script_message, script.getName()))
            .setPositiveButton(R.string.file_browser_run, (dialog, which) -> runScript(script))
            .setNegativeButton(R.string.file_browser_cancel, null)
            .show();
    }

    private void runScript(File script) {
        // Run through bash instead of exec'ing the file directly: files copied from
        // sdcard/other apps usually don't carry the executable bit, so a direct exec
        // would silently fail with a permission error even though the script is fine.
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";

        Intent execIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, Uri.parse("file://" + bashPath), this, TermuxService.class);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{script.getAbsolutePath()});
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, script.getParent());
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, script.getName());

        try {
            startService(execIntent);
            finish(); // Let TermuxActivity come to foreground and show the running session.
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openWithSystemApp(File file) {
        String contentType = guessMimeType(file.getName());
        Uri contentUri = UriUtils.getContentUri(TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY, file.getAbsolutePath());

        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(contentUri, contentType);
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(viewIntent, getString(R.string.file_browser_open_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.file_browser_no_app_found, Toast.LENGTH_SHORT).show();
        }
    }

    private static String guessMimeType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) return "*/*";
        String ext = fileName.substring(dot + 1).toLowerCase();
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return type != null ? type : "*/*";
    }
}
