package io.github.mekhontsev.magicdesk;

import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

/** Semantic adapter for terminal sessions and their optional windows. */
final class DesktopAutomationTerminalWindows {
    private static final int MAX_WRITE_CHARS = 64 * 1024;
    private static final int DEFAULT_READ_CHARS = 16 * 1024;
    private static final int MAX_READ_CHARS = 64 * 1024;
    private static final long OPEN_OBSERVATION_TIMEOUT_MILLIS = 8_000L;

    DesktopAutomationResult open(final JSONObject arguments) {
        try {
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final DesktopExecBackend backend = DesktopExecBackend.parse(
                    args.optString("backend", "shell"));
            if (backend == DesktopExecBackend.SHELL) {
                requireShell();
            } else {
                final var endpoint = TermuxIntegration.inspect(MagicDeskApplication.applicationContext());
                if (!endpoint.available()) {
                    return DesktopAutomationResult.failure(endpoint.permissionRequired
                                    ? DesktopAutomationErrorCode.PERMISSION_REQUIRED
                                    : DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                            endpoint.packageName + ": " + endpoint.error, false);
                }
            }
            final String directory = DesktopExecWorkingDirectory.normalize(
                    args.optString(
                            "directory",
                            backend == DesktopExecBackend.TERMUX
                                    ? TermuxIntegration.homeDirectory(MagicDeskApplication.applicationContext())
                                    : ShellDesktopDirectory.ABSOLUTE_PATH));
            final String resolvedDirectory = directory.isEmpty()
                    ? (backend == DesktopExecBackend.TERMUX
                            ? TermuxIntegration.homeDirectory(MagicDeskApplication.applicationContext())
                            : ShellDesktopDirectory.ABSOLUTE_PATH)
                    : directory;
            final String command = DesktopExecCommand.normalize(
                    args.optString("command", ""));
            if (backend == DesktopExecBackend.SHELL) {
                final ShellFileInfo directoryInfo =
                        ShellAccess.getShellFileInfo(resolvedDirectory);
                if (!directoryInfo.directory || !directoryInfo.readable) {
                    throw new IllegalArgumentException(
                            "terminal directory is not readable");
                }
            }
            final String terminalId = ConsoleTerminalRegistry.nextId();
            final var intent = CommandConsoleActivity.withTerminalId(
                    CommandConsoleActivity.createPreparedCommandIntent(
                            MagicDeskApplication.applicationContext(), command, resolvedDirectory, backend),
                    terminalId);
            final DesktopAutomationResult launch = AutomationToolWindows.open(intent, args);
            if (!launch.success) { return launch; }
            final boolean observed = ConsoleTerminalRegistry.awaitRegistration(
                    terminalId, OPEN_OBSERVATION_TIMEOUT_MILLIS);
            return DesktopAutomationResult.success(
                    "terminal window launch accepted",
                    new JSONObject(launch.data.toString())
                            .put("accepted", true)
                            .put("terminalId", terminalId)
                            .put("backend", backend.wireName)
                            .put("observed", observed)
                            .put("workingDirectory", resolvedDirectory)
                            .put("commandProvided", !command.isEmpty()));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (IOException | RuntimeException error) {
            return unavailable(error);
        }
    }

    DesktopAutomationResult attach(final JSONObject args) {
        try {
            final String id = sessionId(args);
            final var session = ConsoleTerminalRegistry.status(id);
            if (session == null) { return notFound(id); }
            final var intent = CommandConsoleActivity.attachIntent(MagicDeskApplication.applicationContext(), session);
            return openSession(intent, args);
        } catch (JSONException | RuntimeException error) { return unavailable(error); }
    }

    DesktopAutomationResult openTmux(JSONObject args, TmuxSessionProvider.Session session,
            TmuxSessionProvider.Snapshot snapshot) throws JSONException {
        final var intent = TerminalSessions.tmuxIntent(MagicDeskApplication.applicationContext(), session, snapshot);
        return openSession(intent, args);
    }

    private static DesktopAutomationResult openSession(android.content.Intent intent, JSONObject args) throws JSONException {
        final String id = CommandConsoleActivity.terminalId(intent);
        final var previous = ConsoleTerminalRegistry.status(id);
        final long generation = ConsoleTerminalRegistry.attachmentGeneration(id);
        final DesktopAutomationResult launch = AutomationToolWindows.open(intent, args);
        if (!launch.success) return launch;
        // Raising an existing window needs no new registration; moving its view
        // to another display must not acknowledge the old attachment.
        final boolean reuse = previous != null && previous.taskId >= 0
                && previous.displayId == launch.data.getInt("displayId");
        return DesktopAutomationResult.success("terminal attachment requested", new JSONObject(launch.data.toString())
                .put("terminalId", id).put("observed", ConsoleTerminalRegistry.awaitAttachment(id, reuse ? 0 : generation,
                        OPEN_OBSERVATION_TIMEOUT_MILLIS)));
    }

    DesktopAutomationResult detach(final JSONObject args) {
        try {
            final String id = sessionId(args);
            final var session = ConsoleTerminalRegistry.status(id);
            if (!ConsoleTerminalRegistry.hide(id)) { return notFound(id); }
            final boolean retained = session != null && session.tmuxSessionId.isEmpty();
            return DesktopAutomationResult.success(retained ? "terminal window detached; PTY retained"
                            : "tmux client disconnected; tmux session retained",
                    new JSONObject().put("terminalId", id).put("ptyRetained", retained));
        } catch (JSONException | RuntimeException error) { return unavailable(error); }
    }

    DesktopAutomationResult list() {
        try {
            final JSONArray terminals = new JSONArray();
            for (final ConsoleTerminalRegistry.Snapshot snapshot
                    : ConsoleTerminalRegistry.list()) {
                terminals.put(toJson(snapshot));
            }
            return DesktopAutomationResult.success(
                    "terminal sessions listed",
                    new JSONObject()
                            .put("count", terminals.length())
                            .put("terminals", terminals));
        } catch (JSONException | RuntimeException error) {
            return unavailable(error);
        }
    }

    DesktopAutomationResult status(final JSONObject arguments) {
        try {
            final String id = sessionId(arguments);
            try {
                ConsoleTerminalRegistry.refreshWorkingDirectory(id);
            } catch (IllegalArgumentException error) {
                return notFound(id);
            } catch (IOException ignored) {
                // The cached directory remains useful while a process exits.
            }
            try {
                ConsoleTerminalRegistry.refreshForegroundProcess(id);
            } catch (IllegalArgumentException error) {
                return notFound(id);
            } catch (IOException ignored) {
                // The cached process remains useful while a process exits.
            }
            final ConsoleTerminalRegistry.Snapshot snapshot =
                    ConsoleTerminalRegistry.status(id);
            if (snapshot == null) {
                return notFound(id);
            }
            return DesktopAutomationResult.success(
                    "terminal session status", toJson(snapshot)
                            .put("semantics", semantics(id)));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (RuntimeException error) {
            return unavailable(error);
        }
    }

    DesktopAutomationResult read(final JSONObject arguments) {
        try {
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final String id = sessionId(args);
            final String scope = args.optString("scope", "viewport")
                    .trim().toLowerCase(Locale.ROOT);
            if (!scope.equals("viewport") && !scope.equals("transcript") && !scope.equals("command")) {
                throw new IllegalArgumentException(
                        "scope must be viewport, transcript or command");
            }
            final int maxChars = Math.max(1, Math.min(
                    MAX_READ_CHARS,
                    args.optInt("maxChars", DEFAULT_READ_CHARS)));
            final long commandId = args.optLong("commandId", -1);
            if (scope.equals("command") && commandId <= 0) {
                throw new IllegalArgumentException("command scope requires a positive commandId from terminal.status");
            }
            if (!scope.equals("command") && args.has("commandId")) {
                throw new IllegalArgumentException("commandId requires command scope");
            }
            final String full = scope.equals("command") ? ConsoleTerminalRegistry.commandOutput(id, commandId)
                    : ConsoleTerminalRegistry.read(id, scope.equals("transcript"));
            if (full == null) {
                if (scope.equals("command") && ConsoleTerminalRegistry.status(id) != null) {
                    return DesktopAutomationResult.success("command output unavailable",
                            new JSONObject().put("terminalId", id).put("scope", scope)
                                    .put("commandId", commandId).put("available", false));
                }
                return notFound(id);
            }
            final boolean truncated = full.length() > maxChars;
            final String text = readTail(full, maxChars);
            return DesktopAutomationResult.success(
                    "terminal screen read",
                    new JSONObject()
                            .put("terminalId", id)
                            .put("scope", scope)
                            .put("available", true)
                            .put("commandId", scope.equals("command") ? commandId : JSONObject.NULL)
                            .put("text", text)
                            .put("truncated", truncated));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (RuntimeException error) {
            return unavailable(error);
        }
    }

    private static JSONObject semantics(final String id) throws JSONException {
        final var metadata = ConsoleTerminalRegistry.metadata(id);
        if (metadata == null) { return new JSONObject(); }
        final JSONArray commands = new JSONArray();
        for (final var command : ConsoleTerminalRegistry.commands(id)) {
            final var position = command.position();
            commands.put(new JSONObject().put("id", command.id()).put("state", command.state())
                    .put("command", command.command()).put("commandKnown", command.commandKnown())
                    .put("exitCode", command.exitCode() == null ? JSONObject.NULL : command.exitCode())
                    .put("outputAvailable", command.outputAvailable())
                    .put("position", position == null ? JSONObject.NULL : new JSONObject()
                            .put("column", position.column()).put("row", position.row())));
        }
        final JSONArray links = new JSONArray();
        for (final var link : ConsoleTerminalRegistry.links(id)) {
            links.put(new JSONObject().put("row", link.row()).put("startColumn", link.startColumn())
                    .put("endColumn", link.endColumn()).put("uri", link.uri()).put("id", link.id()));
        }
        return new JSONObject().put("shellState", metadata.shellState()).put("commands", commands)
                .put("notification", metadata.notification()).put("notificationSequence", metadata.notificationSequence())
                .put("progress", new JSONObject().put("state", metadata.progressState())
                        .put("percent", metadata.progressPercent()))
                .put("screenLinks", links).put("linksLimitReached", links.length() == 256);
    }

    DesktopAutomationResult write(final JSONObject arguments) {
        try {
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final String id = sessionId(args);
            final String text = args.optString("text", "");
            if (text.isEmpty() || text.length() > MAX_WRITE_CHARS
                    || text.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid terminal input");
            }
            if (!ConsoleTerminalRegistry.write(id, text)) {
                return notFound(id);
            }
            return DesktopAutomationResult.success(
                    "terminal input written",
                    new JSONObject()
                            .put("terminalId", id)
                            .put("characters", text.length()));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (RuntimeException error) {
            return unavailable(error);
        }
    }

    DesktopAutomationResult sendKey(final JSONObject arguments) {
        try {
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final String id = sessionId(args);
            final int keyCode = keyCode(required(args, "key"));
            final int metaState = metaState(args);
            if (!ConsoleTerminalRegistry.sendKey(id, keyCode, metaState)) {
                return notFound(id);
            }
            return DesktopAutomationResult.success(
                    "terminal key sent",
                    new JSONObject()
                            .put("terminalId", id)
                            .put("keyCode", keyCode)
                            .put("metaState", metaState));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (RuntimeException error) {
            return unavailable(error);
        }
    }

    DesktopAutomationResult close(final JSONObject arguments) {
        try {
            final String id = sessionId(arguments);
            if (!ConsoleTerminalRegistry.close(id)) {
                return notFound(id);
            }
            return DesktopAutomationResult.success(
                    "terminal session close accepted",
                    new JSONObject().put("terminalId", id));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (RuntimeException error) {
            return unavailable(error);
        }
    }

    static String readTail(final String text, final int maxChars) {
        int start = Math.max(0, text.length() - maxChars);
        if (start > 0 && start < text.length()
                && Character.isHighSurrogate(text.charAt(start - 1))
                && Character.isLowSurrogate(text.charAt(start))) {
            start++;
        }
        return text.substring(start);
    }

    static JSONObject toJson(final ConsoleTerminalRegistry.Snapshot snapshot)
            throws JSONException {
        final TerminalProcessInfo foreground = snapshot.foregroundProcess;
        return new JSONObject()
                .put("terminalId", snapshot.id)
                .put("taskId", snapshot.taskId)
                .put("displayId", snapshot.displayId)
                .put("attached", snapshot.taskId >= 0)
                .put("focused", snapshot.focused)
                .put("ready", snapshot.ready)
                .put("processId", snapshot.processId)
                .put("columns", snapshot.columns)
                .put("rows", snapshot.rows)
                .put("workingDirectory", snapshot.workingDirectory)
                .put("title", snapshot.title)
                .put("userTitle", snapshot.userTitle)
                .put("tmuxSessionId", snapshot.tmuxSessionId)
                .put("backend", snapshot.backend)
                .put("taskLabel", snapshot.taskLabel(
                        "termux".equals(snapshot.backend)
                                ? "Termux Console" : "Console"))
                .put("foregroundProcess", new JSONObject()
                        .put("known", foreground.isKnown())
                        .put("processId", foreground.processId)
                        .put("processGroupId", foreground.processGroupId)
                        .put("executable", foreground.executable));
    }

    private static String sessionId(final JSONObject arguments) {
        return required(arguments == null ? new JSONObject() : arguments,
                "terminalId");
    }

    private static String required(
            final JSONObject object, final String name) {
        final String value = object.optString(name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static int keyCode(final String value) {
        String name = value.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_');
        switch (name) {
            case "ESC":
                name = "ESCAPE";
                break;
            case "UP":
            case "DOWN":
            case "LEFT":
            case "RIGHT":
                name = "DPAD_" + name;
                break;
            case "PAGEUP":
                name = "PAGE_UP";
                break;
            case "PAGEDOWN":
                name = "PAGE_DOWN";
                break;
            default:
                break;
        }
        final int keyCode = KeyEvent.keyCodeFromString(
                name.startsWith("KEYCODE_") ? name : "KEYCODE_" + name);
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            throw new IllegalArgumentException("unsupported terminal key");
        }
        return keyCode;
    }

    private static int metaState(final JSONObject args) {
        int state = 0;
        if (args.optBoolean("ctrl", false)) {
            state |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        }
        if (args.optBoolean("alt", false)) {
            state |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        }
        if (args.optBoolean("shift", false)) {
            state |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        }
        return state;
    }

    private static void requireShell() throws IOException {
        if (!ShellAccess.isReady()) {
            throw new IOException("shell command service is unavailable");
        }
    }

    private static DesktopAutomationResult notFound(final String id) {
        try {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    "terminal session not found", false,
                    new JSONObject().put("terminalId", id));
        } catch (JSONException impossible) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    "terminal session not found", false);
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
