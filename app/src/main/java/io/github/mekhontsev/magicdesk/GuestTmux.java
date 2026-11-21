package io.github.mekhontsev.magicdesk;

/** A tmux client in one explicitly selected Shroot store, never the host's tmux server. */
final class GuestTmux {
    static java.io.Closeable list(android.content.Context context, GuestEnvironmentCatalog.Entry guest,
            TmuxSessionProvider.Callback callback) {
        var execution = new CommandExecution(context, guest.backend());
        var resources = new OperationResources();
        var handler = new android.os.Handler(android.os.Looper.getMainLooper());
        // EVENT_WAIT: guest catalog response; abort the query if the guest server stops responding.
        Runnable timeout = () -> {
            if (resources.isClosed()) return;
            resources.close();
            callback.onResult(null, new java.io.IOException("tmux session query timed out"));
        };
        var command = resources.reserve();
        resources.reserve().attach(() -> handler.removeCallbacks(timeout));
        handler.postDelayed(timeout, 15_000);
        try {
            command.attach(execution.start(query(guest), "", "tmux", null, (code, output, error) -> handler.post(() -> {
                if (resources.isClosed()) return;
                resources.close();
                if (error != null || code != 0) {
                    callback.onResult(null, error != null ? error : new java.io.IOException(output.strip()));
                    return;
                }
                TmuxSessionProvider.Snapshot snapshot;
                try { snapshot = TmuxSessionProvider.parse(output); }
                catch (IllegalArgumentException invalid) { callback.onResult(null, invalid); return; }
                callback.onResult(snapshot, null);
            })));
        } catch (RuntimeException failure) { resources.close(); throw failure; }
        return resources;
    }

    static String query(GuestEnvironmentCatalog.Entry guest) {
        return GuestEnvironmentCatalog.storeCommand(guest, "exec", "--network", "none", "--", "/bin/sh", "-c",
                "exec </dev/null\n" + TmuxSessionProvider.SESSIONS_COMMAND
                        + "rows=$(tmux -u list-clients -F 'TTY\t#{client_tty}\t#{session_id}') || exit 1\nprintf '%s\\n' \"$rows\"");
    }

    // The target is carried separately from the command, so restoring a console can
    // verify the same guest and operation instead of guessing from shell text.
    record Target(String id, long created, String name) {
        Target {
            if (id == null) {
                if (created != 0) throw new IllegalArgumentException("invalid tmux creation request");
                name = TmuxSessionProvider.normalizeName(name);
            } else if (!TmuxSessionProvider.isSessionId(id) || created < 0 || name == null)
                throw new IllegalArgumentException("invalid tmux attachment");
        }
        static Target attach(TmuxSessionProvider.Session session) { return new Target(session.id, session.createdSeconds, session.name); }
        static Target create(String name) { return new Target(null, 0, name); }
        String[] encode() { return id == null ? new String[]{name} : new String[]{id, Long.toString(created), name}; }
        static Target decode(String[] fields) {
            if (fields.length == 1) return create(fields[0]);
            if (fields.length == 3 && fields[0] != null)
                return new Target(fields[0], Long.parseLong(fields[1]), fields[2]);
            throw new IllegalArgumentException("invalid tmux target");
        }
        String command(GuestEnvironmentCatalog.Entry guest) {
            String action = id == null ? "exec tmux -T hyperlinks new-session -s " + ShellCommandLine.quote(name)
                    : TmuxSessionProvider.sessionCommand(new TmuxSessionProvider.Session(id, "", 0, 0, created),
                            TmuxSessionProvider.attachCommand(id));
            // tmux sends both terminal FDs to its server, possibly in a different launch.
            // Reopen the actual slave in the guest, not the /dev/tty alias or inherited host FDs.
            return GuestEnvironmentCatalog.storeCommand(guest, "exec", "--", "/bin/sh", "-c",
                    "terminal=$(tty) || exit 1\nexec 0<>\"$terminal\" 1>&0\n" + action);
        }
    }
    private GuestTmux() { }
}
