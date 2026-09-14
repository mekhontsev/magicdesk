package io.github.mekhontsev.magicdesk;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded set of explicitly gated persistent shell sessions for MCP. */
final class DesktopAutomationConsoleSessions {
    private static final int MAX_SESSIONS = 8;

    private final Map<String, PersistentAutomationShellSession> mSessions =
            new LinkedHashMap<>();

    synchronized DesktopAutomationResult open(final JSONObject arguments) {
        try {
            requireShell();
            if (mSessions.size() >= MAX_SESSIONS) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.ACTION_FAILED,
                        "maximum console session count reached", false);
            }
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final String directory = args.optString(
                    "directory", ShellDesktopDirectory.ABSOLUTE_PATH);
            final String id = "console-" + UUID.randomUUID().toString().replace("-", "");
            final PersistentAutomationShellSession session =
                    new PersistentAutomationShellSession(directory);
            mSessions.put(id, session);
            return DesktopAutomationResult.success(
                    "console session opened",
                    sessionJson(id, session));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (IOException | RuntimeException error) {
            return unavailable(error);
        }
    }

    DesktopAutomationResult execute(final Context context, final JSONObject arguments) {
        final String id;
        final String command;
        final PersistentAutomationShellSession session;
        TerminalOutputStream stdout = null;
        ShellCommandOutput.Result result = null;
        boolean dispatched = false;
        try {
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            id = required(args, "sessionId");
            command = required(args, "command");
            synchronized (this) {
                session = mSessions.get(id);
            }
            if (session == null) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.INVALID_ARGUMENT,
                        "console session not found", false);
            }
            requireShell();
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
            result = session.execute(command, stdout);
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
            final PersistentAutomationShellSession session =
                    mSessions.remove(id);
            if (session == null) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.INVALID_ARGUMENT,
                        "console session not found", false);
            }
            session.close();
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
            final PersistentAutomationShellSession session = mSessions.get(id);
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
        for (final PersistentAutomationShellSession session
                : mSessions.values()) {
            session.close();
        }
        mSessions.clear();
    }

    private static JSONObject sessionJson(
            final String id,
            final PersistentAutomationShellSession session)
            throws JSONException {
        return new JSONObject()
                .put("sessionId", id)
                .put("workingDirectory", session.workingDirectory());
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
