package io.github.mekhontsev.magicdesk;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Maps MCP tools and resources onto the shared desktop automation gateway. */
final class MagicDeskMcpBackend implements McpBackend {
    private final Context mContext;
    private final AutomationCommands mCommands;

    MagicDeskMcpBackend(final Context context) {
        mContext = context.getApplicationContext();
        mCommands = AutomationCommandRuntime.get(mContext).commands;
    }

    @Override public JSONArray listTools() throws JSONException {
        return describeTools();
    }

    static JSONArray describeTools() throws JSONException {
        final JSONArray tools = AutomationCommandCatalog.create();
        for (int i = 0; i < tools.length(); i++) {
            final JSONObject tool = tools.getJSONObject(i);
            tool.put("description", tool.getString("description") + " Required permission: "
                    + McpAccessPolicy.permissionName(tool.getString("name")) + ".");
        }
        return tools;
    }

    McpBackend scoped(final boolean network) {
        return new McpAuthorizedBackend(this, network ? "network" : "local", () -> {
            final var settings = MagicDeskMcpPreferences.load(mContext);
            return network ? settings.networkAccess : settings.localAccess;
        });
    }

    @Override public JSONObject callTool(String name, JSONObject arguments) throws JSONException {
        return actionResult(mCommands.execute(name, arguments));
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
                return mCommands.stateReader().state().toString(2);
            case "magicdesk://displays":
                return mCommands.stateReader().displays().toString(2);
            case "magicdesk://tasks":
                return mCommands.stateReader()
                        .tasks((Integer) null).toString(2);
            case "magicdesk://apps":
                return mCommands.stateReader().apps().toString(2);
            case "magicdesk://events":
                return mCommands.stateReader().events(0L, 256).toString(2);
            case "magicdesk://diagnostics":
                return mCommands.stateReader().diagnostics().toString(2);
            case "magicdesk://self-test":
                return mCommands.stateReader().selfTest().toString(2);
            default:
                throw new IllegalArgumentException("unknown resource uri");
        }
    }

    static JSONObject actionResult(
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

}
