package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Transport-independent MCP capabilities supplied by MagicDesk. */
interface McpBackend {
    JSONArray listTools() throws JSONException;

    JSONObject callTool(String name, JSONObject arguments)
            throws JSONException;

    JSONArray listResources() throws JSONException;

    String readResource(String uri) throws JSONException;
}
