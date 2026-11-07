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
        } else if (backend == DesktopExecBackend.APP) {
            termux = null;
            uid = android.system.Os.getuid();
            scope = "app:" + uid;
            home = context.getDataDir().toString();
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
        } else if (backend == DesktopExecBackend.APP) {
            if (android.system.Os.getuid() != uid) throw new IllegalStateException("Application identity changed");
        } else if (!ShellAccess.isReady() || ShellAccess.currentSnapshot().uid != uid)
            throw new IllegalStateException("The command service identity changed");
    }

    String shell() {
        return termux == null ? AndroidShell.path(context) : termux.homeDirectory + "/../usr/bin/bash";
    }

    ExecutorSocketEndpoint.Factory endpointFactory() {
        if (backend == DesktopExecBackend.TERMUX) throw new IllegalStateException("Termux admission requires its broker");
        return (name, targetUid, timeout, receiver) -> {
            requireCurrent();
            if (targetUid != uid) throw new SecurityException("Wrong executor identity");
            return backend == DesktopExecBackend.APP
                    ? new ShellUnixEndpoint(name, uid, timeout, receiver, java.nio.file.Path.of(temporaryDirectory()))
                    : ShellAccess.openUnixEndpoint(name, uid, timeout, receiver);
        };
    }

    String temporaryDirectory() {
        return termux != null ? termux.homeDirectory + "/../usr/tmp"
                : RuntimeDataPaths.selected(uid, context.getDataDir().toPath()).temporary().toString();
    }

    ShellCommandSession session(String directory) { return session(directory, true); }

    ShellCommandSession session(String directory, boolean inheritCommandChannel) {
        requireCurrent();
        String token = java.util.UUID.randomUUID().toString().replace("-", "");
        return new ShellCommandSession(directory == null || directory.isEmpty() ? home : directory,
                backend == DesktopExecBackend.SHELL ? new ShellCommandExecutor("__MAGICDESK_CWD_" + token + "__", inheritCommandChannel) : new ShellCommandExecutor("__MAGICDESK_CWD_" + token + "__",
                        () -> {
                            requireCurrent();
                            return backend == DesktopExecBackend.APP ? new LocalCommandStream(context, inheritCommandChannel)
                                    : new TermuxCommandStream(context, termux, inheritCommandChannel);
                        }), token);
    }

    Closeable start(String command, String directory, String stdin, ShellCommandOutput.Sink stdout,
            DetailedCompletion completion) {
        requireCurrent();
        ShellCommandSession session = session(directory);
        AtomicBoolean closed = new AtomicBoolean();
        WORK.execute(() -> {
            try {
                requireCurrent();
                var result = session.execute(invocation(shell(), command, stdin), stdout);
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
