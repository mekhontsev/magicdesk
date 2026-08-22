package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded set of explicitly gated persistent shell sessions for MCP. */
final class DesktopAutomationConsoleSessions {
    private static final int MAX_SESSIONS = 8;
    private static final AtomicLong NEXT_ID = new AtomicLong();

    private final Map<String, ConsoleShellSession> mSessions =
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
            final String id = Long.toString(NEXT_ID.incrementAndGet(), 36);
            final ConsoleShellSession session =
                    new ConsoleShellSession(directory);
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

    DesktopAutomationResult execute(final JSONObject arguments) {
        final String id;
        final String command;
        final ConsoleShellSession session;
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
            final ConsoleShellSession.ExecutionResult result =
                    session.execute(command);
            return DesktopAutomationResult.success(
                    "console command completed",
                    new JSONObject()
                            .put("sessionId", id)
                            .put("exitCode", result.exitCode)
                            .put("output", result.output)
                            .put("workingDirectory",
                                    result.workingDirectory));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (IOException | RuntimeException error) {
            return unavailable(error);
        }
    }

    synchronized DesktopAutomationResult close(final JSONObject arguments) {
        try {
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final String id = required(args, "sessionId");
            final ConsoleShellSession session = mSessions.remove(id);
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
            final ConsoleShellSession session = mSessions.get(id);
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
        for (final ConsoleShellSession session : mSessions.values()) {
            session.close();
        }
        mSessions.clear();
    }

    private static JSONObject sessionJson(
            final String id,
            final ConsoleShellSession session) throws JSONException {
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
