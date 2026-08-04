package com.termux.app.apkbuilder;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.util.HashMap;

/**
 * TermuxMod: runs the bundled builder script headlessly (no visible terminal),
 * using the same {@link AppShell} + {@link TermuxShellEnvironment} machinery
 * Termux itself uses for its "background/plugin" execution mode — this is
 * what guarantees PATH, JAVA_HOME-equivalents and the termux-exec loader are
 * set up correctly, same as a real interactive shell would have.
 *
 * V2: instead of feeding the script's interactive menu with pre-typed
 * keystrokes over stdin (fragile — broke if the number/order of prompts
 * didn't match exactly, which is what caused the "Pilihan tidak valid!"
 * infinite loop / freeze reported after testing), this calls a small
 * non-interactive entrypoint added to the bundled script itself
 * (see {@code assets/apkbuilder/build.sh}, the block right above
 * `while true; do`). The script is invoked as
 * {@code bash build.sh <action> [project_path]} with
 * {@code TERMUXMOD_NONINTERACTIVE=1} set, which the script's own prompts
 * check to skip themselves instead of blocking. Nothing about *what* the
 * script does was changed, only how it's told what to do.
 */
public class ApkBuilderRunner implements AppShell.AppShellClient {

    public interface Listener {
        void onLine(String line, boolean isStderr);
        void onExited(int exitCode);
        /** Called if the process could not even be started (e.g. bad script path). */
        void onFailedToStart();
    }

    /** TermuxMod: action names matching the non-interactive dispatcher added to
     * the bundled build.sh (see the "TermuxMod: NON-INTERACTIVE ENTRYPOINT"
     * block near the end of that file). */
    public static final class Actions {
        public static final String BUILD_DEBUG = "build-debug";
        public static final String BUILD_RELEASE = "build-release";
        public static final String AUTO_SETUP = "auto-setup";
        public static final String CLEAN_CACHE = "clean-cache";
        public static final String IMPORT_BACKUP = "import-backup";
        public static final String EXPORT_BACKUP = "export-backup";
    }

    private final Listener mListener;
    private AppShell mAppShell;

    public ApkBuilderRunner(Listener listener) {
        mListener = listener;
    }

    /**
     * Starts the script running in the background with the given action (see
     * {@link Actions}). {@code projectPath} may be null for actions that don't
     * need one (auto-setup, clean-cache, import-backup, export-backup).
     * Safe to call once per instance.
     */
    public void run(@NonNull Context context, @NonNull String scriptPath, @NonNull String action, String projectPath) {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";

        ExecutionCommand executionCommand = new ExecutionCommand();
        executionCommand.executable = bashPath;
        executionCommand.arguments = projectPath != null
            ? new String[]{scriptPath, action, projectPath}
            : new String[]{scriptPath, action};
        executionCommand.workingDirectory = TermuxConstants.TERMUX_HOME_DIR_PATH;
        executionCommand.runner = Runner.APP_SHELL.getName();
        executionCommand.commandLabel = "APK Builder (" + action + ")";
        executionCommand.backgroundCustomLogLevel = null;
        // Matches what TermuxService itself sets for its own APP_SHELL runner calls,
        // so the environment setup here is identical to Termux's own background execution.
        executionCommand.setShellCommandShellEnvironment = true;

        HashMap<String, String> additionalEnvironment = new HashMap<>();
        additionalEnvironment.put("TERMUXMOD_NONINTERACTIVE", "1");

        mAppShell = AppShell.execute(context, executionCommand, this, new TermuxShellEnvironment(), additionalEnvironment, false);
        if (mAppShell == null) {
            mListener.onFailedToStart();
        }
    }

    /** Stops the running build, if any, by sending it SIGKILL. */
    public void stop() {
        if (mAppShell != null) {
            mAppShell.kill();
        }
    }

    @Override
    public void onAppShellStdoutLine(@NonNull AppShell appShell, @NonNull String line) {
        mListener.onLine(line, false);
    }

    @Override
    public void onAppShellStderrLine(@NonNull AppShell appShell, @NonNull String line) {
        mListener.onLine(line, true);
    }

    @Override
    public void onAppShellExited(AppShell appShell) {
        Integer exitCode = appShell.getExecutionCommand().resultData.exitCode;
        mListener.onExited(exitCode != null ? exitCode : -1);
    }
}
