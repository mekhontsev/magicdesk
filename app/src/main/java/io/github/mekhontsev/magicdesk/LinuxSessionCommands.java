package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared Linux-session operations. MCP, CLI and consoles use the same captured identity. */
final class LinuxSessionCommands {
    static JSONObject execute(String operation, JSONObject args) throws IOException, JSONException {
        if (operation.equals("linux.list")) return new JSONObject().put("sessions", LinuxAutomationLaunch.targets());
        var target = LinuxAutomationLaunch.target(args.getString("sessionId"));
        if (operation.equals("linux.inspect")) {
            JSONObject result = target.bus().context(); result.remove("pipeHelper");
            return result.put("sessionId", target.bus().id).put("graphicsSessionId", target.graphicsId())
                    .put("environment", target.environment()).put("executorUid", target.uid());
        }
        if (operation.equals("linux.launch")) {
            JSONObject result = target.bus().request(6, 0, LinuxApplicationLaunch.prepare(args), 5000);
            return new JSONObject().put("sessionId", target.bus().id).put("accepted", true)
                    .put("pid", Long.parseLong(result.getJSONArray("values").getJSONObject(0).getString("value")));
        }
        throw new IllegalArgumentException("Unknown Linux operation");
    }
    private LinuxSessionCommands() { }
}
