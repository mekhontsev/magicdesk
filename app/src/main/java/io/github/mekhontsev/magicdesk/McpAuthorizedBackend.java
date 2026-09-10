package io.github.mekhontsev.magicdesk;

import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One live authorization boundary per listener; closing it does not own the shared runtime. */
final class McpAuthorizedBackend implements McpBackend {
    private final McpBackend mBackend;
    private final String mScope;
    private final Supplier<McpAccessPolicy> mAccess;

    McpAuthorizedBackend(McpBackend backend, String scope, Supplier<McpAccessPolicy> access) {
        mBackend = backend;
        mScope = scope;
        mAccess = access;
    }

    @Override public JSONArray listTools() throws JSONException { return mBackend.listTools(); }
    @Override public JSONArray listResources() throws JSONException { return mBackend.listResources(); }
    @Override public String readResource(String uri) throws JSONException { return mBackend.readResource(uri); }

    @Override public JSONObject callTool(String name, JSONObject arguments) throws JSONException {
        final McpAccessPolicy access = mAccess.get();
        if (!access.allows(name)) {
            final var permission = McpAccessPolicy.required(name);
            return MagicDeskMcpBackend.actionResult(DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.TOOL_DISABLED, "Permission is not granted: " + name,
                    false, new JSONObject()
                            .put("requiredPermission", permission == null ? "unknown" : permission.id)
                            .put("scope", mScope).put("permissions", access.toJson())));
        }
        final JSONObject result = mBackend.callTool(name, arguments);
        // A content wait may outlive a permission change. Never return its captured UI after revocation.
        if (McpAccessPolicy.required(name) == McpAccessPolicy.Permission.CONTENT
                && !mAccess.get().allows(name)) {
            return MagicDeskMcpBackend.actionResult(DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.TOOL_DISABLED, "Content permission was revoked", false));
        }
        if ("get_state".equals(name)) {
            result.getJSONObject("structuredContent").getJSONObject("data")
                    .put("connection", new JSONObject().put("scope", mScope)
                            .put("permissions", access.toJson()));
            result.getJSONArray("content").getJSONObject(0).put("text",
                    result.getJSONObject("structuredContent").toString(2));
        }
        return result;
    }
}
