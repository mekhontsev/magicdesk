package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class McpAccessPolicyTest {
    @Test public void everyToolHasAnExplicitPermissionAndUnknownToolsAreDenied() throws Exception {
        final McpAccessPolicy observe = new McpAccessPolicy(Set.of());
        final var all = MagicDeskMcpToolCatalog.create();
        for (int i = 0; i < all.length(); i++) {
            final var tool = all.getJSONObject(i);
            final String name = tool.getString("name");
            final String permission = McpAccessPolicy.permissionName(name);
            assertTrue(tool.getString("description").contains("Required permission: " + permission));
            assertEquals(name, "observe".equals(permission), observe.allows(name));
            assertTrue(name, new McpAccessPolicy(Set.of(permission)).allows(name));
        }
        final McpAccessPolicy privileged = new McpAccessPolicy(Set.of("control", "input_tests",
                "content", "files_read", "files_write", "shell", "update"));
        assertFalse(privileged.allows("console.unknown"));
        assertFalse(privileged.allows("new_action"));
        assertFalse(new McpAccessPolicy(Set.of("files_read")).allows("files.create"));
        assertFalse(new McpAccessPolicy(Set.of("files_write")).allows("console.execute"));
    }

    @Test public void permissionChangesTakeEffectWithoutChangingTheCatalog() throws Exception {
        final AtomicReference<McpAccessPolicy> access = new AtomicReference<>(new McpAccessPolicy(Set.of()));
        final McpBackend raw = new McpBackend() {
            @Override public JSONArray listTools() throws org.json.JSONException {
                return MagicDeskMcpToolCatalog.create();
            }
            @Override public JSONObject callTool(String name, JSONObject args) throws org.json.JSONException {
                return MagicDeskMcpBackend.actionResult(DesktopAutomationResult.success("executed", new JSONObject()));
            }
            @Override public JSONArray listResources() { return new JSONArray(); }
            @Override public String readResource(String uri) { return "{}"; }
        };
        final McpBackend local = new McpAuthorizedBackend(raw, "local", access::get);
        final McpBackend network = new McpAuthorizedBackend(raw, "network", () -> new McpAccessPolicy(Set.of()));
        final String catalog = local.listTools().toString();
        final JSONObject denied = local.callTool("files.create", new JSONObject()).getJSONObject("structuredContent");
        assertFalse(denied.getBoolean("success"));
        assertEquals("files_write", denied.getJSONObject("error").getJSONObject("observation").getString("requiredPermission"));
        access.set(new McpAccessPolicy(Set.of("files_write")));
        assertTrue(local.callTool("files.create", new JSONObject()).getJSONObject("structuredContent").getBoolean("success"));
        assertFalse(network.callTool("files.create", new JSONObject()).getJSONObject("structuredContent").getBoolean("success"));
        access.set(new McpAccessPolicy(Set.of()));
        assertFalse(local.callTool("files.create", new JSONObject()).getJSONObject("structuredContent").getBoolean("success"));
        assertEquals(catalog, local.listTools().toString());
        assertEquals(catalog, network.listTools().toString());
    }
}
