package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Live panes on the selected Termux package's default tmux server. */
final class TmuxPanes {
    private TmuxPanes() { }

    record Target(String sessionId, String paneId, PtyEndpoint endpoint) {
        Target {
            if (!TmuxSessionProvider.isSessionId(sessionId) || !paneId.matches("%[0-9]+")) {
                throw new IllegalArgumentException("invalid tmux target");
            }
        }
        String token() { return sessionId + ":" + paneId + ":" + endpoint.wireValue(); }
        static Target parse(String value) throws IOException {
            final String[] fields = value.split(":", -1);
            if (fields.length != 3) throw new IllegalArgumentException("invalid tmux target; use tmux.panes");
            return new Target(fields[0], fields[1], PtyEndpoint.parse(fields[2]));
        }
    }

    record Pane(Target target, String windowId, boolean activeWindow, boolean activePane) { }

    static String listCommand(String sessionId) {
        if (sessionId != null && !TmuxSessionProvider.isSessionId(sessionId)) {
            throw new IllegalArgumentException("invalid tmux session id");
        }
        return "panes=$(tmux list-panes " + (sessionId == null ? "-a" : "-s -t " + ShellCommandLine.quote(sessionId))
                + " -F '#{session_id} #{pane_id} #{window_id} #{window_active} #{pane_active} #{pane_pid} #{pane_tty}')\n"
                + "printf 'MAGICDESK_PANES\\n'\n"
                + "printf '%s\\n' \"$panes\" | while read -r sid pane window wa pa pid tty; do\n"
                + "  ticks=$(\"$target\" --describe-pty \"$pid\" \"$tty\" </dev/null) || continue\n"
                + "  printf '%s %s %s %s %s %s %s %s\\n' \"$sid\" \"$pane\" \"$window\" \"$wa\" \"$pa\" \"$pid\" \"$ticks\" \"$tty\"\n"
                + "done";
    }

    static List<Pane> list(Context context, String sessionId) throws IOException {
        final var result = PtyPeerOutput.runTermux(context, listCommand(sessionId));
        if (!result.success()) throw new IOException(result.usefulMessage());
        return parseList(result.stdout);
    }

    static List<Pane> parseList(String output) throws IOException {
        final String[] lines = output.trim().split("\n");
        if (lines.length == 0 || !lines[0].equals("MAGICDESK_PANES")) throw new IOException("invalid pane list");
        final List<Pane> panes = new ArrayList<>();
        try {
            for (int i = 1; i < lines.length; i++) {
                final String[] f = lines[i].split(" ", -1);
                if (f.length != 8 || !f[2].matches("@[0-9]+") || !f[3].matches("[01]")
                        || !f[4].matches("[01]")) throw new IllegalArgumentException("invalid pane");
                panes.add(new Pane(new Target(f[0], f[1], PtyEndpoint.parse(f[5] + " " + f[6] + " " + f[7])),
                        f[2], f[3].equals("1"), f[4].equals("1")));
            }
        } catch (IllegalArgumentException error) { throw new IOException("invalid pane list", error); }
        return List.copyOf(panes);
    }

    static String emitCommand(Target pane, byte[] bytes) {
        // Session membership and PTY identity are rechecked at execution; never
        // fall back to the active pane when the caller's selection disappeared.
        return "tmux list-panes -s -t " + ShellCommandLine.quote(pane.sessionId())
                + " -F '#{pane_id} #{pane_pid} #{pane_tty}' | {\n"
                + "while read -r id pid tty; do\n"
                + "  if [ \"$id $pid $tty\" = " + ShellCommandLine.quote(pane.paneId() + " "
                        + pane.endpoint().processId() + " " + pane.endpoint().tty()) + " ]; then\n"
                + "    " + PtyPeerOutput.emitCommand(pane.endpoint(), bytes) + "\n    exit $?\n  fi\ndone\n"
                + "printf 'MAGICDESK_EMIT 0 116\\n'\nexit 1\n}";
    }

    static PtyPeerOutput.Receipt emit(Context context, Target target, byte[] bytes) throws IOException {
        return PtyPeerOutput.Receipt.parse(PtyPeerOutput.runTermux(context, emitCommand(target, bytes)).stdout, bytes.length);
    }
}
