package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Minimal MCP 2025-11-25 JSON-RPC implementation for stateless HTTP. */
final class McpJsonRpcHandler implements java.io.Closeable {
    static final String PROTOCOL_VERSION = "2025-11-25";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final McpBackend mBackend;

    McpJsonRpcHandler(final McpBackend backend) {
        if (backend == null) {
            throw new IllegalArgumentException("MCP backend is required");
        }
        mBackend = backend;
    }

    McpJsonRpcResponse handle(final String encoded) {
        final JSONObject request;
        try {
            request = new JSONObject(encoded);
        } catch (JSONException error) {
            return json(400, error(JSONObject.NULL, PARSE_ERROR,
                    "Parse error"));
        }
        final Object id = request.has("id")
                ? request.opt("id") : null;
        if (!"2.0".equals(request.optString("jsonrpc", ""))
                || request.optString("method", "").isEmpty()) {
            return json(400, error(id, INVALID_REQUEST,
                    "Invalid Request"));
        }
        final String method = request.optString("method", "");
        if (id == null) {
            if (method.startsWith("notifications/")) {
                return new McpJsonRpcResponse(202, "");
            }
            return json(400, error(JSONObject.NULL, INVALID_REQUEST,
                    "Requests must include an id"));
        }
        try {
            final JSONObject params = optionalObject(request, "params");
            switch (method) {
                case "initialize":
                    return json(200, result(id, initialize(params)));
                case "ping":
                    return json(200, result(id, new JSONObject()));
                case "tools/list":
                    return json(200, result(id, new JSONObject()
                            .put("tools", mBackend.listTools())));
                case "tools/call":
                    return json(200, result(id, callTool(params)));
                case "resources/list":
                    return json(200, result(id, new JSONObject()
                            .put("resources", mBackend.listResources())));
                case "resources/read":
                    return json(200, result(id, readResource(params)));
                default:
                    return json(404, error(id, METHOD_NOT_FOUND,
                            "Method not found"));
            }
        } catch (IllegalArgumentException | JSONException error) {
            return json(400, error(id, INVALID_PARAMS,
                    cleanMessage(error)));
        } catch (RuntimeException error) {
            DesktopAutomationEventJournal.record(
                    "mcp", method, false, cleanMessage(error));
            return json(500, error(id, INTERNAL_ERROR,
                    "Internal error"));
        }
    }

    @Override
    public void close() {
        mBackend.close();
    }

    private JSONObject initialize(final JSONObject params)
            throws JSONException {
        final String requested = params.optString(
                "protocolVersion", PROTOCOL_VERSION);
        final String selected = "2025-06-18".equals(requested)
                || "2025-03-26".equals(requested)
                || PROTOCOL_VERSION.equals(requested)
                ? requested : PROTOCOL_VERSION;
        return new JSONObject()
                .put("protocolVersion", selected)
                .put("capabilities", new JSONObject()
                        .put("tools", new JSONObject()
                                .put("listChanged", false))
                        .put("resources", new JSONObject()
                                .put("subscribe", false)
                                .put("listChanged", false)))
                .put("serverInfo", new JSONObject()
                        .put("name", "MagicDesk")
                        .put("version", BuildConfig.VERSION_NAME))
                .put("instructions",
                        "Inspect state before mutating desktop tasks. "
                                + "Use wait_for_state after asynchronous "
                                + "desktop or application launches.");
    }

    private JSONObject callTool(final JSONObject params)
            throws JSONException {
        final String name = params.optString("name", "").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("tool name is required");
        }
        final JSONObject arguments = params.optJSONObject("arguments");
        return mBackend.callTool(
                name,
                arguments == null ? new JSONObject() : arguments);
    }

    private JSONObject readResource(final JSONObject params)
            throws JSONException {
        final String uri = params.optString("uri", "").trim();
        if (uri.isEmpty()) {
            throw new IllegalArgumentException("resource uri is required");
        }
        final String text = mBackend.readResource(uri);
        return new JSONObject().put("contents", new JSONArray()
                .put(new JSONObject()
                        .put("uri", uri)
                        .put("mimeType", "application/json")
                        .put("text", text)));
    }

    private static JSONObject optionalObject(
            final JSONObject parent, final String key) {
        if (!parent.has(key) || parent.isNull(key)) {
            return new JSONObject();
        }
        final JSONObject value = parent.optJSONObject(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        return value;
    }

    private static JSONObject result(
            final Object id, final JSONObject value) throws JSONException {
        return new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", value);
    }

    private static JSONObject error(
            final Object id,
            final int code,
            final String message) {
        try {
            return new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id == null ? JSONObject.NULL : id)
                    .put("error", new JSONObject()
                            .put("code", code)
                            .put("message", message));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static McpJsonRpcResponse json(
            final int status, final JSONObject body) {
        return new McpJsonRpcResponse(status, body.toString());
    }

    private static String cleanMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message.trim();
    }
}
