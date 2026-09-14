package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class PersistentAutomationShellSession {
    private final CommandExecutor mExecutor;
    private final String mMarker;
    private final AtomicLong mResetGeneration = new AtomicLong();
    private volatile String mWorkingDirectory;
    private volatile boolean mDirectoryChangePending = true;

    PersistentAutomationShellSession(String directory) {
        this(directory, null, UUID.randomUUID().toString().replace("-", ""));
    }

    PersistentAutomationShellSession(String directory, CommandExecutor executor, String token) {
        if (token == null || !token.matches("[a-zA-Z0-9]+")) {
            throw new IllegalArgumentException("invalid console session token");
        }
        mWorkingDirectory = requireDirectory(directory);
        mMarker = "__MAGICDESK_CWD_" + token + "__";
        mExecutor = executor == null ? new PersistentAutomationCommandExecutor(mMarker) : executor;
    }

    String workingDirectory() { return mWorkingDirectory; }

    ShellCommandOutput.Result execute(String command) throws IOException { return execute(command, null); }

    // Serialize directory preparation, execution and state commit together.
    // close/cancel deliberately do not acquire this monitor.
    synchronized ShellCommandOutput.Result execute(String command, ShellCommandOutput.Sink stdout)
            throws IOException {
        if (command == null || command.trim().isEmpty() || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("missing or invalid console command");
        }
        final long generation = mResetGeneration.get();
        try {
            final var result = mExecutor.execute(wrap(command, stdout != null), stdout);
            mWorkingDirectory = requireDirectory(result.workingDirectory());
            mDirectoryChangePending = generation != mResetGeneration.get();
            return result;
        } catch (IOException | RuntimeException error) {
            mDirectoryChangePending = true;
            throw error;
        }
    }

    void cancelCurrentCommand() {
        mResetGeneration.incrementAndGet();
        mDirectoryChangePending = true;
        mExecutor.cancelCurrent();
    }

    void close() { mExecutor.close(); }

    private static String requireDirectory(String directory) {
        if (directory == null || !directory.startsWith("/")) {
            throw new IllegalArgumentException("working directory must be absolute");
        }
        return DesktopExecWorkingDirectory.normalize(directory);
    }

    private String wrap(String command, boolean redirectStdout) {
        final String status = "__magicdesk_status_" + mMarker;
        final StringBuilder shell = new StringBuilder(status + "=0\n");
        if (mDirectoryChangePending) shell.append("cd -- ")
                .append(ShellCommandLine.quote(mWorkingDirectory)).append(" || ").append(status).append("=$?\n");
        shell.append("if [ \"$").append(status).append("\" -eq 0 ]; then\n{\n")
                .append(command).append('\n').append(status).append("=$?\n}")
                .append(redirectStdout ? "\n" : " 2>&1\n").append("fi\n");
        final String completion = "printf '\\n" + mMarker + "%s\\t%s\\n' \"$" + status + "\" \"$PWD\"";
        // Each pipe has its own boundary: a fast stdout drain cannot discard
        // delayed stderr, and binary output never passes through a UTF-8 decoder.
        return shell.append(completion).append('\n').append(completion)
                .append(" >&2\nunset ").append(status).toString();
    }

    interface CommandExecutor {
        ShellCommandOutput.Result execute(String command, ShellCommandOutput.Sink stdout) throws IOException;
        default void cancelCurrent() { }
        default void close() { }
    }
}
