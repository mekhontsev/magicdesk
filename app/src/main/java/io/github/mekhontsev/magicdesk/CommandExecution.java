package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** An explicitly selected command identity, captured once and never silently elevated or replaced. */
final class CommandExecution {
    private static final ExecutorService WORK = Executors.newCachedThreadPool(r -> new Thread(r, "CommandExecution"));
    interface Completion { void complete(int exitCode, String output, Throwable error); }
    final DesktopExecBackend backend;
    final TermuxIntegration.Endpoint termux;
    final int uid;
    final String scope;
    final String home;
    private final Context context;

    CommandExecution(Context context, DesktopExecBackend backend) {
        this.context = context.getApplicationContext();
        this.backend = backend;
        if (backend == DesktopExecBackend.TERMUX) {
            termux = TermuxIntegration.inspect(context);
            termux.requireAvailable();
            uid = termux.uid;
            scope = termux.packageName;
            home = termux.homeDirectory;
        } else {
            if (!ShellAccess.isReady()) throw new IllegalStateException("Privileged command service is unavailable");
            termux = null;
            uid = ShellAccess.currentSnapshot().uid;
            scope = "shell:" + uid;
            home = "/data/local/tmp";
        }
    }

    Closeable start(String command, String directory, String label, String stdin, Completion completion) {
        if (termux != null) {
            var result = TermuxIntegration.runBackgroundShellCommandForResult(context, termux,
                    boundedOutput(command), label, directory.isEmpty() ? home : directory, 0, stdin,
                    (value, error) -> completion.complete(value == null ? -1 : value.exitCode,
                            value == null ? "" : value.usefulMessage(), error != null ? error
                                    : value != null && !value.success() ? new IllegalStateException(value.usefulMessage()) : null));
            return () -> TermuxCommandResultReceiver.cancel(result);
        }
        if (stdin != null) throw new IllegalArgumentException("Shell command input is not supported by this runner");
        if (!ShellAccess.isReady() || ShellAccess.currentSnapshot().uid != uid)
            throw new IllegalStateException("The command service identity changed");
        ShellCommandSession session = new ShellCommandSession(directory.isEmpty() ? home : directory);
        AtomicBoolean closed = new AtomicBoolean();
        WORK.execute(() -> {
            try {
                // A recipe may use exec/exit; its shell must not consume the transport's completion boundary.
                var result = session.execute("/system/bin/sh -c " + ShellCommandLine.quote(command));
                if (!closed.get()) completion.complete(result.exitCode(), result.output(), null);
            } catch (IOException | RuntimeException error) {
                if (!closed.get()) completion.complete(-1, "", error);
            } finally { session.close(); }
        });
        return () -> { closed.set(true); session.close(); };
    }

    // RUN_COMMAND retains output until exit. Drain it continuously with bounded storage and the real exit status.
    static String boundedOutput(String command) {
        return "set -o pipefail\n{\n" + command + "\n} 2>&1 | tail -c 16384 >&2\n";
    }
}
