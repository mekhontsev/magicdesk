package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

final class ConsoleShellSession {
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();
    private static final String MARKER_PREFIX = "__MAGICDESK_CWD_";

    private final CommandExecutor mExecutor;
    private final String mMarker;
    private String mWorkingDirectory;

    ConsoleShellSession(final String initialDirectory) {
        this(
                initialDirectory,
                ShellAccess::executeForConsole,
                Long.toHexString(NEXT_SESSION_ID.incrementAndGet())
                        + Long.toHexString(System.nanoTime()));
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
        mExecutor = executor;
        mMarker = MARKER_PREFIX + sessionToken + "__";
    }

    String workingDirectory() {
        return mWorkingDirectory;
    }

    void setWorkingDirectory(final String directory) {
        mWorkingDirectory = requireAbsoluteDirectory(directory);
    }

    ExecutionResult execute(final String command) throws IOException {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("missing console command");
        }
        final ShellAccess.CommandResult raw = mExecutor.execute(
                wrap(command));
        final ParsedOutput parsed = parseOutput(raw.output);
        if (parsed.workingDirectory != null) {
            mWorkingDirectory = parsed.workingDirectory;
        }
        return new ExecutionResult(
                raw.exitCode,
                parsed.output,
                mWorkingDirectory);
    }

    private String wrap(final String command) {
        final String statusVariable = "__magicdesk_status_"
                + mMarker.substring(MARKER_PREFIX.length(),
                        mMarker.length() - 2);
        final StringBuilder shell = new StringBuilder();
        shell.append("cd -- ")
                .append(ShellCommandLine.quote(mWorkingDirectory))
                .append(" || exit $?\n")
                .append(command);
        if (!command.endsWith("\n")) {
            shell.append('\n');
        }
        shell.append(statusVariable).append("=$?\n")
                .append("printf '\\n")
                .append(mMarker)
                .append("%s\\n' \"$PWD\"\n")
                .append("exit \"$")
                .append(statusVariable)
                .append("\"");
        return shell.toString();
    }

    private ParsedOutput parseOutput(final String rawOutput) {
        final String delimiter = "\n" + mMarker;
        final int markerStart = rawOutput.lastIndexOf(delimiter);
        if (markerStart < 0) {
            // Commands such as `exit` can end the shell before it reports its cwd.
            return new ParsedOutput(rawOutput, null);
        }
        final int pathStart = markerStart + delimiter.length();
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
