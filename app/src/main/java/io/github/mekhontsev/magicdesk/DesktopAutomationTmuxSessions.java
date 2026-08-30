package io.github.mekhontsev.magicdesk;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/** Typed automation adapter for persistent tmux sessions inside Termux. */
final class DesktopAutomationTmuxSessions {
    private final Context mContext;
    private final DesktopAutomationTerminalWindows mTerminals;

    DesktopAutomationTmuxSessions(
            final Context context,
            final DesktopAutomationTerminalWindows terminals) {
        mContext = context.getApplicationContext();
        mTerminals = terminals;
    }

    DesktopAutomationResult list() {
        if (!TermuxIntegration.isInstalled(mContext)) {
            return listResult(TmuxSessionProvider.Snapshot.unavailable(
                    "Termux is not installed"));
        }
        if (!TermuxIntegration.isAvailable(mContext)) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.PERMISSION_REQUIRED,
                    "Termux Run command permission is unavailable",
                    false);
        }
        try {
            return listResult(TmuxSessionProvider.listBlocking(mContext));
        } catch (IOException | RuntimeException error) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.CONSOLE_ACCESS_FAILED,
                    ShellAccess.usefulMessage(error),
                    true);
        }
    }

    DesktopAutomationResult open(final JSONObject arguments) {
        try {
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final String sessionId = args.optString("sessionId", "").trim();
            final String requestedName = args.optString("name", "").trim();
            if (sessionId.isEmpty() == requestedName.isEmpty()) {
                throw new IllegalArgumentException(
                        "exactly one of sessionId or name is required");
            }
            if (!TermuxIntegration.isAvailable(mContext)) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.PERMISSION_REQUIRED,
                        "Termux Run command permission is unavailable",
                        false);
            }
            final TmuxSessionProvider.Snapshot snapshot =
                    TmuxSessionProvider.listBlocking(mContext);
            if (!snapshot.available) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                        snapshot.detail,
                        false);
            }

            final String command;
            final String tmuxSessionId;
            final String tmuxSessionName;
            if (!sessionId.isEmpty()) {
                if (!TmuxSessionProvider.isSessionId(sessionId)) {
                    throw new IllegalArgumentException(
                            "invalid tmux session id");
                }
                final TmuxSessionProvider.Session session =
                        snapshot.find(sessionId);
                if (session == null) {
                    return DesktopAutomationResult.failure(
                            DesktopAutomationErrorCode.INVALID_ARGUMENT,
                            "tmux session not found",
                            false,
                            new JSONObject().put("sessionId", sessionId));
                }
                command = TmuxSessionProvider.attachCommand(session.id);
                tmuxSessionId = session.id;
                tmuxSessionName = session.name;
            } else {
                tmuxSessionName = TmuxSessionProvider.normalizeName(
                        requestedName);
                command = TmuxSessionProvider.openOrCreateCommand(
                        tmuxSessionName);
                TmuxSessionProvider.Session existing = null;
                for (final TmuxSessionProvider.Session session
                        : snapshot.sessions) {
                    if (session.name.equals(tmuxSessionName)) {
                        existing = session;
                        break;
                    }
                }
                tmuxSessionId = existing == null ? "" : existing.id;
            }

            final DesktopAutomationResult terminal = mTerminals.open(
                    new JSONObject()
                            .put("backend", "termux")
                            .put("directory", TermuxIntegration.HOME_DIRECTORY)
                            .put("command", command));
            if (!terminal.success) {
                return terminal;
            }
            final JSONObject data = new JSONObject(terminal.data.toString())
                    .put("tmuxSessionId", tmuxSessionId)
                    .put("tmuxSessionName", tmuxSessionName);
            return DesktopAutomationResult.success(
                    "tmux terminal window launch accepted", data);
        } catch (IllegalArgumentException | JSONException error) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    ShellAccess.usefulMessage(error),
                    false);
        } catch (IOException | RuntimeException error) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.CONSOLE_ACCESS_FAILED,
                    ShellAccess.usefulMessage(error),
                    true);
        }
    }

    private static DesktopAutomationResult listResult(
            final TmuxSessionProvider.Snapshot snapshot) {
        try {
            final JSONArray sessions = new JSONArray();
            for (final TmuxSessionProvider.Session session
                    : snapshot.sessions) {
                sessions.put(new JSONObject()
                        .put("sessionId", session.id)
                        .put("name", session.name)
                        .put("windows", session.windows)
                        .put("attachedClients", session.attachedClients)
                        .put("attached", session.attached())
                        .put("createdSeconds", session.createdSeconds));
            }
            return DesktopAutomationResult.success(
                    snapshot.available
                            ? "tmux sessions listed"
                            : snapshot.detail,
                    new JSONObject()
                            .put("available", snapshot.available)
                            .put("detail", snapshot.detail)
                            .put("count", sessions.length())
                            .put("sessions", sessions));
        } catch (JSONException impossible) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.CONSOLE_ACCESS_FAILED,
                    "could not serialize tmux sessions",
                    false);
        }
    }
}
