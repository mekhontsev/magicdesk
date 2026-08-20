package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public final class McpJsonRpcHandlerTest {
    private final McpJsonRpcHandler mHandler =
            new McpJsonRpcHandler(new FakeBackend());

    @Test
    public void initializesCurrentProtocol() throws Exception {
        final McpJsonRpcResponse response = mHandler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":1,"
                        + "\"method\":\"initialize\",\"params\":{}}" );
        final JSONObject result = new JSONObject(response.body)
                .getJSONObject("result");

        assertEquals(200, response.httpStatus);
        assertEquals(McpJsonRpcHandler.PROTOCOL_VERSION,
                result.getString("protocolVersion"));
        assertEquals("MagicDesk",
                result.getJSONObject("serverInfo").getString("name"));
    }

    @Test
    public void routesToolsAndResources() throws Exception {
        final McpJsonRpcResponse tool = mHandler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":2,"
                        + "\"method\":\"tools/call\",\"params\":{"
                        + "\"name\":\"example\","
                        + "\"arguments\":{\"value\":7}}}" );
        final McpJsonRpcResponse resource = mHandler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":3,"
                        + "\"method\":\"resources/read\",\"params\":{"
                        + "\"uri\":\"magicdesk://state\"}}" );

        assertEquals(7, new JSONObject(tool.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent")
                .getInt("value"));
        assertEquals("{\"state\":true}", new JSONObject(resource.body)
                .getJSONObject("result").getJSONArray("contents")
                .getJSONObject(0).getString("text"));
    }

    @Test
    public void acceptsNotificationsWithoutResponseBody() {
        final McpJsonRpcResponse response = mHandler.handle(
                "{\"jsonrpc\":\"2.0\","
                        + "\"method\":\"notifications/initialized\"}" );

        assertEquals(202, response.httpStatus);
        assertTrue(response.body.isEmpty());
    }

    @Test
    public void rejectsMalformedAndUnknownRequests() throws Exception {
        final McpJsonRpcResponse malformed = mHandler.handle("not-json");
        final McpJsonRpcResponse unknown = mHandler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":4,"
                        + "\"method\":\"unknown\"}" );

        assertEquals(-32700, new JSONObject(malformed.body)
                .getJSONObject("error").getInt("code"));
        assertEquals(-32601, new JSONObject(unknown.body)
                .getJSONObject("error").getInt("code"));
        assertFalse(unknown.body.isEmpty());
    }

    private static final class FakeBackend implements McpBackend {
        @Override
        public JSONArray listTools() throws JSONException {
            return new JSONArray().put(new JSONObject().put("name", "example"));
        }

        @Override
        public JSONObject callTool(
                final String name, final JSONObject arguments)
                throws JSONException {
            return new JSONObject()
                    .put("structuredContent", new JSONObject()
                            .put("value", arguments.optInt("value")));
        }

        @Override
        public JSONArray listResources() throws JSONException {
            return new JSONArray().put(new JSONObject()
                    .put("uri", "magicdesk://state"));
        }

        @Override
        public String readResource(final String uri) {
            return "{\"state\":true}";
        }
    }
}
