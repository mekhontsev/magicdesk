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
    interface DetailedCompletion { void complete(ShellCommandOutput.Result result, Throwable error); }
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
            home = RuntimeDataPaths.privileged(uid).root().toString();
        }
    }

    Closeable start(String command, String directory, String label, String stdin, Completion completion) {
        return start(command, directory, stdin, null, (result, error) -> completion.complete(
                result == null ? -1 : result.exitCode(), result == null ? "" : result.output(), error));
    }

    void requireCurrent() {
        if (termux != null) {
            var current = TermuxIntegration.inspect(context);
            current.requireAvailable();
            if (current.uid != uid || !current.packageName.equals(scope))
                throw new IllegalStateException("Termux identity changed");
        } else if (!ShellAccess.isReady() || ShellAccess.currentSnapshot().uid != uid)
            throw new IllegalStateException("The command service identity changed");
    }

    String shell() {
        return termux == null ? AndroidShell.path(context) : termux.homeDirectory + "/../usr/bin/bash";
    }

    Closeable start(String command, String directory, String stdin, ShellCommandOutput.Sink stdout,
            DetailedCompletion completion) {
        requireCurrent();
        String token = java.util.UUID.randomUUID().toString().replace("-", "");
        ShellCommandSession session = new ShellCommandSession(directory.isEmpty() ? home : directory,
                termux == null ? null : new ShellCommandExecutor("__MAGICDESK_CWD_" + token + "__",
                        () -> new TermuxCommandStream(context, termux)), token);
        AtomicBoolean closed = new AtomicBoolean();
        WORK.execute(() -> {
            try {
                requireCurrent();
                String setup = termux == null ? "" : AutomationCommandRuntime.get(context).prepareTermux(termux);
                var result = session.execute(setup + invocation(shell(), command, stdin), stdout);
                if (!closed.get()) completion.complete(result, null);
            } catch (IOException | RuntimeException error) {
                if (!closed.get()) completion.complete(null, error);
            } finally { session.close(); }
        });
        return () -> { closed.set(true); session.close(); };
    }

    static String invocation(String shell, String command, String stdin) {
        // exec/exit belong to the child shell, not the transport's completion boundary.
        return (stdin == null ? "" : "printf '%s' " + ShellCommandLine.quote(stdin) + " | ")
                + ShellCommandLine.quote(shell) + " -c " + ShellCommandLine.quote(command)
                + (stdin == null ? " </dev/null" : "");
    }
}
