package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** On-demand access to persistent tmux sessions owned by the Termux UID. */
final class TmuxSessionProvider {
    private static final long RESULT_TIMEOUT_MILLIS = 5_000L;
    private static final int MAX_SESSION_NAME_LENGTH = 64;
    private static final String AVAILABLE_MARKER =
            "__MAGICDESK_TMUX_AVAILABLE__";
    private static final String UNAVAILABLE_MARKER =
            "__MAGICDESK_TMUX_UNAVAILABLE__";
    private static final Pattern SESSION_ID = Pattern.compile("\\$[0-9]+");
    private static final String LIST_COMMAND =
            "if ! command -v tmux >/dev/null 2>&1; then\n"
            + "  printf '" + UNAVAILABLE_MARKER + "\\n'\n"
            + "  exit 0\n"
            + "fi\n"
            + "printf '" + AVAILABLE_MARKER + "\\n'\n"
            + "tmux list-sessions -F '"
            + "#{session_id}\t#{session_name}\t#{session_windows}"
            + "\t#{session_attached}\t#{session_created}' "
            + "2>/dev/null || true";

    private TmuxSessionProvider() {
    }

    static void list(
            final Context context,
            final Callback callback) {
        if (context == null || callback == null) {
            throw new IllegalArgumentException(
                    "tmux context and callback are required");
        }
        final Context appContext = context.getApplicationContext();
        if (!TermuxIntegration.isInstalled(appContext)) {
            callback.onResult(Snapshot.unavailable(
                    "Termux is not installed"), null);
            return;
        }
        if (!TermuxIntegration.isAvailable(appContext)) {
            callback.onResult(null, new IOException(
                    "Termux Run command permission is unavailable"));
            return;
        }
        try {
            TermuxIntegration.runBackgroundShellCommandForResult(
                    appContext,
                    LIST_COMMAND,
                    "MagicDesk tmux sessions",
                    TermuxIntegration.HOME_DIRECTORY,
                    RESULT_TIMEOUT_MILLIS,
                    (result, error) -> {
                        if (error != null) {
                            callback.onResult(null, error);
                            return;
                        }
                        if (result == null || !result.success()) {
                            callback.onResult(null, new IOException(
                                    result == null
                                            ? "missing Termux command result"
                                            : result.usefulMessage()));
                            return;
                        }
                        try {
                            callback.onResult(parse(result.stdout), null);
                        } catch (IllegalArgumentException parseError) {
                            callback.onResult(null, parseError);
                        }
                    });
        } catch (RuntimeException error) {
            callback.onResult(null, error);
        }
    }

    static Snapshot listBlocking(final Context context) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("tmux session query cannot block the UI");
        }
        final CountDownLatch complete = new CountDownLatch(1);
        final Snapshot[] snapshot = new Snapshot[1];
        final Throwable[] failure = new Throwable[1];
        list(context, (result, error) -> {
            snapshot[0] = result;
            failure[0] = error;
            complete.countDown();
        });
        try {
            if (!complete.await(
                    RESULT_TIMEOUT_MILLIS + 1_000L,
                    TimeUnit.MILLISECONDS)) {
                throw new IOException("tmux session query timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("tmux session query was interrupted", error);
        }
        if (failure[0] != null) {
            throw new IOException(
                    ShellAccess.usefulMessage(failure[0]), failure[0]);
        }
        if (snapshot[0] == null) {
            throw new IOException("tmux session query returned no result");
        }
        return snapshot[0];
    }

    static Snapshot parse(final String output) {
        final String normalized = output == null
                ? "" : output.replace("\r", "");
        final String[] lines = normalized.split("\n", -1);
        if (lines.length == 0 || UNAVAILABLE_MARKER.equals(lines[0])) {
            return Snapshot.unavailable("tmux is not installed in Termux");
        }
        if (!AVAILABLE_MARKER.equals(lines[0])) {
            throw new IllegalArgumentException(
                    "invalid tmux session response");
        }
        final List<Session> sessions = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isEmpty()) {
                continue;
            }
            final String[] fields = lines[index].split("\t", -1);
            if (fields.length != 5 || !isSessionId(fields[0])) {
                throw new IllegalArgumentException(
                        "invalid tmux session record");
            }
            try {
                final int windows = Integer.parseInt(fields[2]);
                final int attachedClients = Integer.parseInt(fields[3]);
                final long createdSeconds = Long.parseLong(fields[4]);
                if (windows < 0 || attachedClients < 0
                        || createdSeconds < 0L) {
                    throw new NumberFormatException("negative tmux metadata");
                }
                sessions.add(new Session(
                        fields[0],
                        fields[1],
                        windows,
                        attachedClients,
                        createdSeconds));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                        "invalid tmux session metadata", error);
            }
        }
        return Snapshot.available(sessions);
    }

    static String attachCommand(final String sessionId) {
        if (!isSessionId(sessionId)) {
            throw new IllegalArgumentException("invalid tmux session id");
        }
        return "exec tmux attach-session -t "
                + ShellCommandLine.quote(sessionId);
    }

    static String openOrCreateCommand(final String name) {
        final String normalized = normalizeName(name);
        return "exec tmux new-session -A -s "
                + ShellCommandLine.quote(normalized);
    }

    static String normalizeName(final String value) {
        final String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > MAX_SESSION_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "tmux session name must contain 1 to 64 characters");
        }
        for (int index = 0; index < name.length(); index++) {
            final char character = name.charAt(index);
            if (Character.isISOControl(character)
                    || character == ':' || character == '.') {
                throw new IllegalArgumentException(
                        "tmux session name cannot contain controls, ':' or '.'");
            }
        }
        return name;
    }

    static boolean isSessionId(final String value) {
        return value != null && SESSION_ID.matcher(value).matches();
    }

    interface Callback {
        void onResult(Snapshot snapshot, Throwable error);
    }

    static final class Snapshot {
        final boolean available;
        final String detail;
        final List<Session> sessions;

        private Snapshot(
                final boolean available,
                final String detail,
                final List<Session> sessions) {
            this.available = available;
            this.detail = detail == null ? "" : detail;
            this.sessions = Collections.unmodifiableList(
                    new ArrayList<>(sessions));
        }

        static Snapshot available(final List<Session> sessions) {
            return new Snapshot(true, "", sessions);
        }

        static Snapshot unavailable(final String detail) {
            return new Snapshot(false, detail, Collections.emptyList());
        }

        Session find(final String sessionId) {
            for (final Session session : sessions) {
                if (session.id.equals(sessionId)) {
                    return session;
                }
            }
            return null;
        }
    }

    static final class Session {
        final String id;
        final String name;
        final int windows;
        final int attachedClients;
        final long createdSeconds;

        Session(
                final String id,
                final String name,
                final int windows,
                final int attachedClients,
                final long createdSeconds) {
            this.id = id;
            this.name = name;
            this.windows = windows;
            this.attachedClients = attachedClients;
            this.createdSeconds = createdSeconds;
        }

        boolean attached() {
            return attachedClients > 0;
        }
    }
}
