package io.github.mekhontsev.magicdesk;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

/** Shared command execution; transport adapters own framing and client authorization. */
final class AutomationCommands implements AutoCloseable {
    private final Context mContext;
    private final DesktopAutomationController mAutomation;
    private final DesktopAutomationFileTools mFiles =
            new DesktopAutomationFileTools();
    private final DesktopAutomationConsoleSessions mConsole =
            new DesktopAutomationConsoleSessions();
    private final DesktopAutomationTerminalWindows mTerminals =
            new DesktopAutomationTerminalWindows();
    private final DesktopAutomationTmuxSessions mTmux;
    private final AutomationFileTransfers mTransfers;
    private final AndroidUiAutomation mAndroidUi;

    AutomationCommands(final Context context) {
        mContext = context.getApplicationContext();
        mAutomation = new DesktopAutomationController(mContext);
        mAndroidUi = new AndroidUiAutomation(mContext);
        mTmux = new DesktopAutomationTmuxSessions(mContext, mTerminals);
        mTransfers = new AutomationFileTransfers(mContext.getFilesDir().toPath().resolve("automation-transfers"),
                new ShellAutomationTransferStorage());
    }

    @Override
    public void close() {
        mAndroidUi.close();
        mConsole.closeAll();
    }

    DesktopAutomationStateReader stateReader() { return mAutomation.stateReader(); }

    DesktopAutomationResult execute(
            final String name,
            final JSONObject arguments) throws JSONException {
        try {
            AutomationCommandArguments.check(name, arguments);
            return executeChecked(name, arguments);
        } catch (IllegalArgumentException | JSONException error) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    ShellAccess.usefulMessage(error), false);
        } catch (RuntimeException error) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    ShellAccess.usefulMessage(error), false);
        }
    }

    private DesktopAutomationResult executeChecked(
            final String name,
            final JSONObject arguments) throws JSONException {
        final JSONObject args = arguments == null
                ? new JSONObject() : arguments;
        final JSONObject data;
        if (name.startsWith("ui.") || name.startsWith("input.") || name.startsWith("device.")) {
            try {
                final JSONObject result = mAndroidUi.execute(name, args);
                if (result.has("accepted") && !result.getBoolean("accepted")) {
                    return DesktopAutomationResult.failure(DesktopAutomationErrorCode.ACTION_FAILED,
                            "Android declined the UI action", false, result);
                }
                return DesktopAutomationResult.success("ok", result);
            } catch (java.io.IOException error) {
                return DesktopAutomationResult.failure(DesktopAutomationErrorCode.ACTION_FAILED,
                        ShellAccess.usefulMessage(error), false);
            }
        }
        if (name.equals("app.update") || name.equals("app.update_status")) {
            try {
                return DesktopAutomationResult.success("ok", name.equals("app.update") ? MagicDeskAppUpdates.start(mContext, args)
                        : MagicDeskAppUpdates.status(mContext, args.getString("updateId")));
            } catch (java.io.IOException error) {
                return DesktopAutomationResult.failure(DesktopAutomationErrorCode.ACTION_FAILED,
                        ShellAccess.usefulMessage(error), true);
            }
        }
        if (name.startsWith("files.upload_") || name.startsWith("files.download_")) {
            try {
                return DesktopAutomationResult.success("ok", mTransfers.execute(name, args));
            } catch (java.io.IOException error) {
                return DesktopAutomationResult.failure(DesktopAutomationErrorCode.FILE_ACCESS_FAILED,
                        ShellAccess.usefulMessage(error), true);
            }
        }
        switch (name) {
            case "get_state":
                data = mAutomation.stateReader().state();
                data.put("automationAwake", mAndroidUi.awakeState());
                return DesktopAutomationResult.success("ok", data);
            case "get_pointer_state":
                data = mAutomation.stateReader().pointerState(args);
                return DesktopAutomationResult.success("ok", data);
            case "list_displays":
                data = mAutomation.stateReader().displays();
                return DesktopAutomationResult.success("ok", data);
            case "list_tasks":
                data = mAutomation.stateReader().tasks(args);
                return DesktopAutomationResult.success("ok", data);
            case "list_apps":
                data = mAutomation.stateReader().apps(args);
                return DesktopAutomationResult.success("ok", data);
            case "get_app_presentation":
                data = mAutomation.stateReader().appPresentation(args);
                return DesktopAutomationResult.success("ok", data);
            case "list_ui_elements":
                data = mAutomation.stateReader().uiElements(args);
                return DesktopAutomationResult.success("ok", data);
            case "get_events":
                data = mAutomation.stateReader().events(
                        Math.max(0L, args.optLong("afterId", 0L)),
                        Math.max(1, args.optInt("limit", 100)));
                return DesktopAutomationResult.success("ok", data);
            case "get_diagnostics":
                data = mAutomation.stateReader().diagnostics();
                return DesktopAutomationResult.success("ok", data);
            case "get_self_test":
                data = mAutomation.stateReader().selfTest(args.optBoolean("includeReport", false));
                return DesktopAutomationResult.success("ok", data);
            case "get_termux_x11_status":
                data = mAutomation.stateReader().termuxX11Status();
                return DesktopAutomationResult.success("ok", data);
            case "wait_for_state":
                return mAutomation.waitFor(args);
            default:
                break;
        }
        if (name.startsWith("files.")
                || name.startsWith("console.")
                || name.startsWith("terminal.")
                || name.startsWith("tmux.")) {
            switch (name) {
                case "files.list":
                    return mFiles.list(args);
                case "files.stat":
                    return mFiles.stat(args);
                case "files.create":
                    return mFiles.create(args);
                case "files.rename":
                    return mFiles.rename(args);
                case "console.open":
                    return mConsole.open(args);
                case "console.execute":
                    return mConsole.execute(args);
                case "console.status":
                    return mConsole.status(args);
                case "console.close":
                    return mConsole.close(args);
                case "terminal.open":
                    return mTerminals.open(args);
                case "terminal.attach":
                    return mTerminals.attach(args);
                case "terminal.detach":
                    return mTerminals.detach(args);
                case "terminal.list":
                    return mTerminals.list();
                case "terminal.status":
                    return mTerminals.status(args);
                case "terminal.read":
                    return mTerminals.read(args);
                case "terminal.write":
                    return mTerminals.write(args);
                case "terminal.send_key":
                    return mTerminals.sendKey(args);
                case "terminal.close":
                    return mTerminals.close(args);
                case "tmux.list":
                    return mTmux.list();
                case "tmux.open":
                    return mTmux.open(args);
                default:
                    return DesktopAutomationResult.failure("unknown gated tool");
            }
        }
        final DesktopAutomationResult result = mAutomation.execute(
                name,
                args,
                true); // The adapter has established its caller's authority.
        return result;
    }

}
