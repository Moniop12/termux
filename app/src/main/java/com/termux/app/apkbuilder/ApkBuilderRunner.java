package com.termux.app.apkbuilder;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

/**
 * TermuxMod: runs the user's builder script headlessly (no visible terminal),
 * using the same {@link AppShell} + {@link TermuxShellEnvironment} machinery
 * Termux itself uses for its "background/plugin" execution mode — this is
 * what guarantees PATH, JAVA_HOME-equivalents and the termux-exec loader are
 * set up correctly, same as a real interactive shell would have.
 *
 * The whole point of this class is to feed the script's own interactive menu
 * with the keystrokes it would normally get typed by hand (see
 * {@link StdinScripts}), and surface stdout/stderr live via {@link Listener}
 * instead of only after the process exits.
 */
public class ApkBuilderRunner implements AppShell.AppShellClient {

    public interface Listener {
        void onLine(String line, boolean isStderr);
        void onExited(int exitCode);
        /** Called if the process could not even be started (e.g. bad script path). */
        void onFailedToStart();
    }

    /** TermuxMod: canned stdin keystroke sequences matching this specific builder
     * script's main-menu numbering (1=Debug, 2=Release, 5=Import Backup, 0=Exit).
     * If the user's script menu differs, these need to be adjusted to match. */
    public static final class StdinScripts {
        // "1" build debug -> Enter (use last project) -> Enter (dismiss "press enter to
        // continue" after build finishes) -> "0" exit back at the main menu.
        public static final String BUILD_DEBUG = "1\n\n\n0";
        public static final String BUILD_RELEASE = "2\n\n\n0";
        // "5" import backup -> "y" confirm restore -> "0" exit once back at main menu.
        public static final String IMPORT_BACKUP = "5\ny\n0";
        public static final String AUTO_SETUP = "3\n0";
        public static final String EXPORT_BACKUP = "6\n0";
    }

    private final Listener mListener;
    private AppShell mAppShell;

    public ApkBuilderRunner(Listener listener) {
        mListener = listener;
    }

    /** Starts the script running in the background. Safe to call once per instance. */
    public void run(@NonNull Context context, @NonNull String scriptPath, @NonNull String stdinScript) {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";

        ExecutionCommand executionCommand = new ExecutionCommand();
        executionCommand.executable = bashPath;
        executionCommand.arguments = new String[]{scriptPath};
        executionCommand.workingDirectory = TermuxConstants.TERMUX_HOME_DIR_PATH;
        executionCommand.stdin = stdinScript;
        executionCommand.runner = Runner.APP_SHELL.getName();
        executionCommand.commandLabel = "APK Builder";
        executionCommand.backgroundCustomLogLevel = null;
        // Matches what TermuxService itself sets for its own APP_SHELL runner calls,
        // so the environment setup here is identical to Termux's own background execution.
        executionCommand.setShellCommandShellEnvironment = true;

        mAppShell = AppShell.execute(context, executionCommand, this, new TermuxShellEnvironment(), null, false);
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
