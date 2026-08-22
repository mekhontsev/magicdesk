package io.github.mekhontsev.magicdesk;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Maps MCP tools and resources onto the shared desktop automation gateway. */
final class MagicDeskMcpBackend implements McpBackend {
    private final Context mContext;
    private final DesktopAutomationController mAutomation;
    private final DesktopAutomationFileTools mFiles =
            new DesktopAutomationFileTools();
    private final DesktopAutomationConsoleSessions mConsole =
            new DesktopAutomationConsoleSessions();

    MagicDeskMcpBackend(final Context context) {
        mContext = context.getApplicationContext();
        mAutomation = new DesktopAutomationController(mContext);
    }

    @Override
    public void close() {
        mConsole.closeAll();
    }

    @Override
    public JSONArray listTools() throws JSONException {
        final MagicDeskMcpPreferences.Values settings =
                MagicDeskMcpPreferences.load(mContext);
        return MagicDeskMcpToolCatalog.create(
                settings.developerTools, settings.shellTools);
    }

    @Override
    public JSONObject callTool(
            final String name,
            final JSONObject arguments) throws JSONException {
        try {
            return callToolChecked(name, arguments);
        } catch (IllegalArgumentException error) {
            return actionResult(DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    ShellAccess.usefulMessage(error), false));
        } catch (RuntimeException error) {
            return actionResult(DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    ShellAccess.usefulMessage(error), false));
        }
    }

    private JSONObject callToolChecked(
            final String name,
            final JSONObject arguments) throws JSONException {
        final JSONObject args = arguments == null
                ? new JSONObject() : arguments;
        final JSONObject data;
        switch (name) {
            case "get_state":
                data = mAutomation.stateReader().state();
                return successResult(data);
            case "list_displays":
                data = mAutomation.stateReader().displays();
                return successResult(data);
            case "list_tasks":
                data = mAutomation.stateReader().tasks(args);
                return successResult(data);
            case "list_apps":
                data = mAutomation.stateReader().apps(args);
                return successResult(data);
            case "get_events":
                data = mAutomation.stateReader().events(
                        Math.max(0L, args.optLong("afterId", 0L)),
                        Math.max(1, args.optInt("limit", 100)));
                return successResult(data);
            case "get_diagnostics":
                data = mAutomation.stateReader().diagnostics();
                return successResult(data);
            case "get_self_test":
                data = mAutomation.stateReader().selfTest();
                return successResult(data);
            case "wait_for_state":
                return actionResult(mAutomation.waitFor(args));
            default:
                break;
        }
        if (name.startsWith("files.")
                || name.startsWith("console.")) {
            if (!MagicDeskMcpPreferences.load(mContext).shellTools) {
                return actionResult(DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.TOOL_DISABLED,
                        "Files and Console automation tools are disabled",
                        false));
            }
            switch (name) {
                case "files.list":
                    return actionResult(mFiles.list(args));
                case "files.stat":
                    return actionResult(mFiles.stat(args));
                case "files.create":
                    return actionResult(mFiles.create(args));
                case "files.rename":
                    return actionResult(mFiles.rename(args));
                case "console.open":
                    return actionResult(mConsole.open(args));
                case "console.execute":
                    return actionResult(mConsole.execute(args));
                case "console.status":
                    return actionResult(mConsole.status(args));
                case "console.close":
                    return actionResult(mConsole.close(args));
                default:
                    return errorResult("unknown gated tool");
            }
        }
        final DesktopAutomationResult result = mAutomation.execute(
                name,
                args,
                MagicDeskMcpPreferences.load(mContext).developerTools);
        return actionResult(result);
    }

    @Override
    public JSONArray listResources() throws JSONException {
        return new JSONArray()
                .put(resource(
                        "magicdesk://state",
                        "Desktop state",
                        "Current MagicDesk, shell and desktop session state"))
                .put(resource(
                        "magicdesk://displays",
                        "Displays",
                        "Connected Android displays and supported modes"))
                .put(resource(
                        "magicdesk://tasks",
                        "Tasks",
                        "Current Android task and windowing state"))
                .put(resource(
                        "magicdesk://apps",
                        "Applications",
                        "Launchable Android application activities"))
                .put(resource(
                        "magicdesk://events",
                        "Automation events",
                        "Bounded structured automation event history"))
                .put(resource(
                        "magicdesk://diagnostics",
                        "Compatibility diagnostics",
                        "Full MagicDesk compatibility report"))
                .put(resource(
                        "magicdesk://self-test",
                        "Desktop self-test",
                        "Latest built-in desktop self-test result"));
    }

    @Override
    public String readResource(final String uri) throws JSONException {
        switch (uri) {
            case "magicdesk://state":
                return mAutomation.stateReader().state().toString(2);
            case "magicdesk://displays":
                return mAutomation.stateReader().displays().toString(2);
            case "magicdesk://tasks":
                return mAutomation.stateReader()
                        .tasks((Integer) null).toString(2);
            case "magicdesk://apps":
                return mAutomation.stateReader().apps().toString(2);
            case "magicdesk://events":
                return mAutomation.stateReader().events(0L, 256).toString(2);
            case "magicdesk://diagnostics":
                return mAutomation.stateReader().diagnostics().toString(2);
            case "magicdesk://self-test":
                return mAutomation.stateReader().selfTest().toString(2);
            default:
                throw new IllegalArgumentException("unknown resource uri");
        }
    }

    private static JSONObject successResult(final JSONObject data)
            throws JSONException {
        return actionResult(DesktopAutomationResult.success("ok", data));
    }

    private static JSONObject errorResult(final String message)
            throws JSONException {
        return actionResult(DesktopAutomationResult.failure(message));
    }

    private static JSONObject actionResult(
            final DesktopAutomationResult result) throws JSONException {
        final JSONObject structured = result.toJson();
        final JSONArray content = new JSONArray().put(new JSONObject()
                .put("type", "text")
                .put("text", structured.toString(2)));
        if (result.image != null) {
            content.put(new JSONObject()
                    .put("type", "image")
                    .put("data", result.image.base64Data)
                    .put("mimeType", result.image.mimeType));
        }
        return new JSONObject()
                .put("content", content)
                .put("structuredContent", structured)
                .put("isError", !result.success);
    }

    private static JSONObject resource(
            final String uri,
            final String name,
            final String description) throws JSONException {
        return new JSONObject()
                .put("uri", uri)
                .put("name", name)
                .put("description", description)
                .put("mimeType", "application/json");
    }

    private static Integer optionalInteger(
            final JSONObject object, final String key) {
        if (object == null || !object.has(key)) {
            return null;
        }
        final Object value = object.opt(key);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        final long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " is out of range");
        }
        return Integer.valueOf((int) number);
    }
}
