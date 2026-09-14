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
        final var endpoint = TermuxIntegration.inspect(mContext);
        if (!endpoint.installed) {
            return listResult(TmuxSessionProvider.Snapshot.unavailable(
                    endpoint.packageName + ": " + endpoint.error));
        }
        if (!endpoint.available()) {
            return DesktopAutomationResult.failure(
                    endpoint.permissionRequired ? DesktopAutomationErrorCode.PERMISSION_REQUIRED
                            : DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                    endpoint.packageName + ": " + endpoint.error,
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
            final var endpoint = TermuxIntegration.inspect(mContext);
            if (!endpoint.available()) {
                return DesktopAutomationResult.failure(
                        endpoint.permissionRequired ? DesktopAutomationErrorCode.PERMISSION_REQUIRED
                                : DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                        endpoint.packageName + ": " + endpoint.error,
                        false);
            }
            final var session = TmuxSessionProvider.prepareBlocking(mContext,
                    sessionId.isEmpty() ? null : sessionId, requestedName.isEmpty() ? null : requestedName);
            final var snapshot = TmuxSessionProvider.listBlocking(mContext);
            final DesktopAutomationResult terminal = mTerminals.openTmux(args, session, snapshot);
            if (!terminal.success) return terminal;
            return DesktopAutomationResult.success("tmux terminal window launch accepted",
                    new JSONObject(terminal.data.toString()).put("tmuxSessionId", session.id)
                            .put("tmuxSessionName", session.name));
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

    DesktopAutomationResult panes(JSONObject args) {
        try {
            final String sessionId = args.has("sessionId") ? args.getString("sessionId") : null;
            final JSONArray panes = new JSONArray();
            for (final var pane : TmuxPanes.list(mContext, sessionId)) {
                panes.put(new JSONObject().put("target", pane.target().token())
                        .put("sessionId", pane.target().sessionId()).put("paneId", pane.target().paneId())
                        .put("windowId", pane.windowId()).put("activeWindow", pane.activeWindow())
                        .put("activePane", pane.activePane()));
            }
            return DesktopAutomationResult.success("Live tmux output targets listed",
                    new JSONObject().put("count", panes.length()).put("panes", panes));
        } catch (IllegalArgumentException | JSONException error) {
            return DesktopAutomationResult.failure(DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    ShellAccess.usefulMessage(error), false);
        } catch (IOException | RuntimeException error) {
            return DesktopAutomationResult.failure(DesktopAutomationErrorCode.CONSOLE_ACCESS_FAILED,
                    ShellAccess.usefulMessage(error), false);
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
