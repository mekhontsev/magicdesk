package io.github.mekhontsev.magicdesk;

import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

/** Semantic automation adapter for visible interactive Console windows. */
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
            } else if (!TermuxIntegration.isAvailable(
                    MagicDeskApplication.applicationContext())) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.PERMISSION_REQUIRED,
                        "Termux Run command permission is unavailable",
                        false);
            }
            final String directory = DesktopExecWorkingDirectory.normalize(
                    args.optString(
                            "directory",
                            backend == DesktopExecBackend.TERMUX
                                    ? TermuxIntegration.HOME_DIRECTORY
                                    : ShellDesktopDirectory.ABSOLUTE_PATH));
            final String resolvedDirectory = directory.isEmpty()
                    ? (backend == DesktopExecBackend.TERMUX
                            ? TermuxIntegration.HOME_DIRECTORY
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
            if (!DesktopRuntimeBridge.openConsole(
                    resolvedDirectory,
                    command,
                    terminalId,
                    backend)) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                        "desktop host is unavailable", true);
            }
            final boolean observed = ConsoleTerminalRegistry.awaitRegistration(
                    terminalId, OPEN_OBSERVATION_TIMEOUT_MILLIS);
            return DesktopAutomationResult.success(
                    "terminal window launch accepted",
                    new JSONObject()
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

    DesktopAutomationResult list() {
        try {
            final JSONArray terminals = new JSONArray();
            for (final ConsoleTerminalRegistry.Snapshot snapshot
                    : ConsoleTerminalRegistry.list()) {
                terminals.put(toJson(snapshot));
            }
            return DesktopAutomationResult.success(
                    "terminal windows listed",
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
            final ConsoleTerminalRegistry.Snapshot snapshot =
                    ConsoleTerminalRegistry.status(id);
            if (snapshot == null) {
                return notFound(id);
            }
            return DesktopAutomationResult.success(
                    "terminal window status", toJson(snapshot));
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
            if (!scope.equals("viewport") && !scope.equals("transcript")) {
                throw new IllegalArgumentException(
                        "scope must be viewport or transcript");
            }
            final int maxChars = Math.max(1, Math.min(
                    MAX_READ_CHARS,
                    args.optInt("maxChars", DEFAULT_READ_CHARS)));
            final String full = ConsoleTerminalRegistry.read(
                    id, scope.equals("transcript"));
            if (full == null) {
                return notFound(id);
            }
            final boolean truncated = full.length() > maxChars;
            final String text = truncated
                    ? full.substring(full.length() - maxChars) : full;
            return DesktopAutomationResult.success(
                    "terminal screen read",
                    new JSONObject()
                            .put("terminalId", id)
                            .put("scope", scope)
                            .put("text", text)
                            .put("truncated", truncated));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (RuntimeException error) {
            return unavailable(error);
        }
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
                    "terminal window close accepted",
                    new JSONObject().put("terminalId", id));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (RuntimeException error) {
            return unavailable(error);
        }
    }

    static JSONObject toJson(final ConsoleTerminalRegistry.Snapshot snapshot)
            throws JSONException {
        return new JSONObject()
                .put("terminalId", snapshot.id)
                .put("taskId", snapshot.taskId)
                .put("displayId", snapshot.displayId)
                .put("focused", snapshot.focused)
                .put("ready", snapshot.ready)
                .put("processId", snapshot.processId)
                .put("columns", snapshot.columns)
                .put("rows", snapshot.rows)
                .put("workingDirectory", snapshot.workingDirectory)
                .put("title", snapshot.title)
                .put("backend", snapshot.backend);
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
                    "terminal window not found", false,
                    new JSONObject().put("terminalId", id));
        } catch (JSONException impossible) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    "terminal window not found", false);
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
