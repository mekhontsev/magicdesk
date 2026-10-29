package io.github.mekhontsev.magicdesk;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded persistent shells shared by command adapters; the selected identity never changes. */
final class DesktopAutomationConsoleSessions {
    private static final int MAX_SESSIONS = 8;
    private boolean mClosed;
    private final boolean mInheritCommandChannel;
    DesktopAutomationConsoleSessions() { this(true); }
    DesktopAutomationConsoleSessions(boolean inheritCommandChannel) { mInheritCommandChannel = inheritCommandChannel; }

    private record Entry(ShellCommandSession shell, LinuxAutomationLaunch.Target linux) { }
    private final Map<String, Entry> mSessions =
            new LinkedHashMap<>();

    synchronized DesktopAutomationResult open(final JSONObject arguments) {
        try {
            if (mClosed) throw new IOException("console owner is closed");
            if (mSessions.size() >= MAX_SESSIONS) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.ACTION_FAILED,
                        "maximum console session count reached", false);
            }
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final var linux = args.has("linuxSessionId") ? LinuxAutomationLaunch.target(args.getString("linuxSessionId")) : null;
            if (linux == null) requireShell();
            final String directory = args.optString(
                    "directory", linux == null ? ShellDesktopDirectory.ABSOLUTE_PATH : linux.bus().context().getString("directory"));
            final String id = "console-" + UUID.randomUUID().toString().replace("-", "");
            String token = UUID.randomUUID().toString().replace("-", "");
            final ShellCommandSession session = linux == null ? new ShellCommandSession(directory, mInheritCommandChannel)
                    : new ShellCommandSession(directory, new ShellCommandExecutor("__MAGICDESK_CWD_" + token + "__",
                            () -> new LinuxCommandStream(linux)), token);
            final Entry entry = new Entry(session, linux);
            mSessions.put(id, entry);
            return DesktopAutomationResult.success(
                    "console session opened",
                    sessionJson(id, entry));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (IOException | RuntimeException error) {
            return unavailable(error);
        }
    }

    DesktopAutomationResult execute(final Context context, final JSONObject arguments) {
        final String id;
        final String command;
        final Entry entry;
        TerminalOutputStream stdout = null;
        ShellCommandOutput.Result result = null;
        boolean dispatched = false;
        try {
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            id = required(args, "sessionId");
            command = required(args, "command");
            synchronized (this) {
                entry = mSessions.get(id);
            }
            if (entry == null) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.INVALID_ARGUMENT,
                        "console session not found", false);
            }
            requireCurrent(entry);
            if (args.has("stdout")) {
                final JSONObject target = args.getJSONObject("stdout");
                final String mime = target.has("mimeType") ? target.getString("mimeType") : null;
                if (mime != null && !mime.equals("image/png")) {
                    throw new IllegalArgumentException("unsupported stdout mimeType");
                }
                final var destination = TerminalOutputTarget.resolve(
                        target.has("terminalId") ? target.getString("terminalId") : null,
                        target.has("tmuxTarget") ? target.getString("tmuxTarget") : null);
                destination.requireAvailable(context);
                stdout = destination.stream(context, mime);
            }
            dispatched = true;
            result = entry.shell.execute(command, stdout);
            if (stdout != null) stdout.finish();
            final JSONObject data = new JSONObject()
                    .put("sessionId", id)
                    .put("exitCode", result.exitCode())
                    .put("workingDirectory", result.workingDirectory());
            if (stdout == null) data.put("output", result.output());
            else data.put("stderr", result.stderr()).put("stderrTruncated", result.stderrTruncated())
                    .put("stdoutDelivery", delivery(stdout));
            return DesktopAutomationResult.success(
                    "console command completed", data);
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (IOException | RuntimeException error) {
            if (dispatched) {
                final JSONObject data = new JSONObject();
                try {
                    data.put("exitCode", result == null ? JSONObject.NULL : result.exitCode())
                            .put("safeToRetry", false);
                    if (result != null) data.put("stderr", result.stderr())
                            .put("stderrTruncated", result.stderrTruncated());
                    if (error instanceof ShellCommandOutput.Failure failure) {
                        data.put("stderr", failure.stderr).put("stderrTruncated", failure.stderrTruncated);
                    }
                    if (stdout != null) data.put("stdoutDelivery", delivery(stdout));
                } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
                return DesktopAutomationResult.failure(DesktopAutomationErrorCode.CONSOLE_ACCESS_FAILED,
                        ShellAccess.usefulMessage(error), false, data);
            }
            return unavailable(error);
        }
    }

    private static JSONObject delivery(TerminalOutputStream output) throws JSONException {
        return new JSONObject().put("completed", output.completed)
                .put("sourceBytes", output.sourceBytes).put("bytesWritten", output.bytesWritten)
                .put("writeUnconfirmed", output.writeUnconfirmed).put("errno", output.errno);
    }

    synchronized DesktopAutomationResult close(final JSONObject arguments) {
        try {
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final String id = required(args, "sessionId");
            final Entry session =
                    mSessions.remove(id);
            if (session == null) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.INVALID_ARGUMENT,
                        "console session not found", false);
            }
            session.shell.close();
            return DesktopAutomationResult.success(
                    "console session closed",
                    new JSONObject().put("sessionId", id));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        }
    }

    synchronized DesktopAutomationResult status(
            final JSONObject arguments) {
        try {
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final String id = required(args, "sessionId");
            final Entry session = mSessions.get(id);
            if (session == null) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.INVALID_ARGUMENT,
                        "console session not found", false);
            }
            return DesktopAutomationResult.success(
                    "console session status",
                    sessionJson(id, session));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        }
    }

    synchronized void closeAll() {
        mClosed = true;
        for (final Entry session
                : mSessions.values()) {
            session.shell.close();
        }
        mSessions.clear();
    }

    private static JSONObject sessionJson(
            final String id,
            final Entry session)
            throws JSONException {
        return new JSONObject()
                .put("sessionId", id)
                    .put("workingDirectory", session.shell.workingDirectory())
                    .put("linuxSessionId", session.linux == null ? JSONObject.NULL : session.linux.bus().id)
                    .put("available", session.linux == null ? ShellAccess.isReady() : session.linux.bus().alive());
    }

    synchronized DesktopAutomationResult list() throws JSONException {
        var list = new org.json.JSONArray();
        for (var entry : mSessions.entrySet()) list.put(sessionJson(entry.getKey(), entry.getValue()));
        return DesktopAutomationResult.success("console sessions", new JSONObject().put("sessions", list));
    }

    DesktopAutomationResult cancel(JSONObject args) throws JSONException {
        String id = required(args, "sessionId");
        Entry entry;
        synchronized (this) { entry = mSessions.get(id); }
        if (entry == null) return DesktopAutomationResult.failure(DesktopAutomationErrorCode.INVALID_ARGUMENT, "console session not found", false);
        entry.shell.cancelCurrentCommand();
        return DesktopAutomationResult.success("console command cancelled", sessionJson(id, entry));
    }

    private static void requireCurrent(Entry entry) throws IOException {
        if (entry.linux == null) requireShell();
        else if (!entry.linux.bus().alive()) throw new IOException("Linux session ended; open a console in a new session explicitly");
    }

    private static String required(
            final JSONObject object,
            final String name) {
        final String value = object.optString(name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static void requireShell() throws IOException {
        if (!ShellAccess.isReady()) {
            throw new IOException("shell command service is unavailable");
        }
    }

    private static DesktopAutomationResult invalid(final Throwable error) {
        return DesktopAutomationResult.failure(
                DesktopAutomationErrorCode.INVALID_ARGUMENT,
                ShellAccess.usefulMessage(error), false);
    }

    private static DesktopAutomationResult unavailable(final Throwable error) {
        return DesktopAutomationResult.failure(
                DesktopAutomationErrorCode.CONSOLE_ACCESS_FAILED,
                ShellAccess.usefulMessage(error), true);
    }
}
