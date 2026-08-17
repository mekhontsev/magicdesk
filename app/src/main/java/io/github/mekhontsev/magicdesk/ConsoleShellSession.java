package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

final class ConsoleShellSession {
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();
    private static final String MARKER_PREFIX = "__MAGICDESK_CWD_";
    private static final Pattern EXIT_COMMAND = Pattern.compile(
            "^\\s*exit(?:\\s+([0-9]+))?\\s*$");

    private final CommandExecutor mExecutor;
    private final String mMarker;
    private final AtomicLong mShellResetGeneration = new AtomicLong();
    private volatile String mWorkingDirectory;
    private volatile boolean mDirectoryChangePending = true;

    ConsoleShellSession(final String initialDirectory) {
        mWorkingDirectory = requireAbsoluteDirectory(initialDirectory);
        final String sessionToken =
                Long.toHexString(NEXT_SESSION_ID.incrementAndGet())
                        + Long.toHexString(System.nanoTime());
        mMarker = MARKER_PREFIX + sessionToken + "__";
        mExecutor = new PersistentConsoleCommandExecutor(mMarker);
    }

    ConsoleShellSession(
            final String initialDirectory,
            final CommandExecutor executor,
            final String sessionToken) {
        if (executor == null) {
            throw new IllegalArgumentException("missing command executor");
        }
        if (sessionToken == null || !sessionToken.matches("[a-zA-Z0-9]+")) {
            throw new IllegalArgumentException("invalid console session token");
        }
        mWorkingDirectory = requireAbsoluteDirectory(initialDirectory);
        mMarker = MARKER_PREFIX + sessionToken + "__";
        mExecutor = executor;
    }

    String workingDirectory() {
        return mWorkingDirectory;
    }

    void setWorkingDirectory(final String directory) {
        mWorkingDirectory = requireAbsoluteDirectory(directory);
        mDirectoryChangePending = true;
    }

    ExecutionResult execute(final String command) throws IOException {
        return execute(command, null);
    }

    ExecutionResult execute(
            final String command,
            final OutputListener outputListener) throws IOException {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("missing console command");
        }
        final long resetGeneration = mShellResetGeneration.get();
        final StringBuilder streamed = outputListener == null
                ? null : new StringBuilder();
        final ShellAccess.CommandResult raw;
        try {
            raw = mExecutor.execute(
                    wrap(command, mDirectoryChangePending),
                    output -> {
                        if (streamed != null && output != null
                                && !output.isEmpty()) {
                            streamed.append(output);
                            outputListener.onOutput(output);
                        }
                    });
        } catch (IOException | RuntimeException error) {
            // The executor discards a failed shell; its replacement must
            // resume in the requested or last confirmed directory.
            mDirectoryChangePending = true;
            throw error;
        }
        final ParsedOutput parsed = parseOutput(raw.output);
        if (outputListener != null
                && parsed.output.startsWith(streamed.toString())
                && streamed.length() < parsed.output.length()) {
            outputListener.onOutput(
                    parsed.output.substring(streamed.length()));
        }
        if (parsed.workingDirectory != null) {
            mWorkingDirectory = parsed.workingDirectory;
            mDirectoryChangePending = resetGeneration
                    != mShellResetGeneration.get();
        }
        return new ExecutionResult(
                raw.exitCode,
                parsed.output,
                mWorkingDirectory);
    }

    List<String> commandSearchPath() throws IOException {
        final ExecutionResult result = execute(
                "command printf '%s' \"$PATH\"");
        if (result.exitCode != 0) {
            throw new IOException("could not read the shell PATH");
        }
        final LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (final String entry : result.output.split(":", -1)) {
            final String candidate = entry.isEmpty()
                    ? mWorkingDirectory
                    : entry.startsWith("/")
                            ? entry : mWorkingDirectory + "/" + entry;
            try {
                paths.add(ShellFilePathPolicy.normalizeShellAbsolute(candidate));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed PATH entries without disabling completion.
            }
        }
        return new ArrayList<>(paths);
    }

    void cancelCurrentCommand() {
        mShellResetGeneration.incrementAndGet();
        mDirectoryChangePending = true;
        mExecutor.cancelCurrent();
    }

    void close() {
        mExecutor.close();
    }

    private String wrap(
            final String command,
            final boolean applyWorkingDirectory) {
        final String statusVariable = "__magicdesk_status_"
                + mMarker.substring(MARKER_PREFIX.length(),
                        mMarker.length() - 2);
        final StringBuilder shell = new StringBuilder();
        shell.append(statusVariable).append("=0\n");
        if (applyWorkingDirectory) {
            shell.append("cd -- ")
                    .append(ShellCommandLine.quote(mWorkingDirectory))
                    .append(" || ")
                    .append(statusVariable)
                    .append("=$?\n");
        }
        shell.append("if [ \"$")
                .append(statusVariable)
                .append("\" -eq 0 ]; then\n")
                .append(command);
        if (!command.endsWith("\n")) {
            shell.append('\n');
        }
        shell.append(statusVariable).append("=$?\n")
                .append("fi\n")
                .append("printf '\\n")
                .append(mMarker)
                .append("%s\\t%s\\n' \"$")
                .append(statusVariable)
                .append("\" \"$PWD\"\n")
                .append("unset ")
                .append(statusVariable);
        return shell.toString();
    }

    private ParsedOutput parseOutput(final String rawOutput) {
        final String delimiter = "\n" + mMarker;
        final int markerStart = rawOutput.lastIndexOf(delimiter);
        if (markerStart < 0) {
            // Commands such as `exit` can end the shell before it reports its cwd.
            return new ParsedOutput(rawOutput, null);
        }
        final int statusStart = markerStart + delimiter.length();
        final int statusEnd = rawOutput.indexOf('\t', statusStart);
        if (statusEnd < 0) {
            return new ParsedOutput(rawOutput, null);
        }
        final int pathStart = statusEnd + 1;
        final int pathEnd = rawOutput.indexOf('\n', pathStart);
        if (pathEnd < 0) {
            return new ParsedOutput(rawOutput, null);
        }
        final String directory = rawOutput.substring(pathStart, pathEnd);
        if (!isAbsoluteDirectory(directory)) {
            return new ParsedOutput(rawOutput, null);
        }
        return new ParsedOutput(rawOutput.substring(0, markerStart), directory);
    }

    static boolean isExitCommand(final String command) {
        return command != null && EXIT_COMMAND.matcher(command).matches();
    }

    private static String requireAbsoluteDirectory(final String directory) {
        if (!isAbsoluteDirectory(directory)) {
            throw new IllegalArgumentException("working directory must be absolute");
        }
        return directory;
    }

    private static boolean isAbsoluteDirectory(final String directory) {
        return directory != null
                && directory.startsWith("/")
                && directory.indexOf('\n') < 0
                && directory.indexOf('\r') < 0;
    }

    interface CommandExecutor {
        ShellAccess.CommandResult execute(String command) throws IOException;

        default ShellAccess.CommandResult execute(
                final String command,
                final OutputListener outputListener) throws IOException {
            return execute(command);
        }

        default void cancelCurrent() {
        }

        default void close() {
        }
    }

    interface OutputListener {
        void onOutput(String output);
    }

    static final class ExecutionResult {
        final int exitCode;
        final String output;
        final String workingDirectory;

        ExecutionResult(
                final int exitCode,
                final String output,
                final String workingDirectory) {
            this.exitCode = exitCode;
            this.output = output;
            this.workingDirectory = workingDirectory;
        }
    }

    private static final class ParsedOutput {
        final String output;
        final String workingDirectory;

        ParsedOutput(final String output, final String workingDirectory) {
            this.output = output;
            this.workingDirectory = workingDirectory;
        }
    }
}
