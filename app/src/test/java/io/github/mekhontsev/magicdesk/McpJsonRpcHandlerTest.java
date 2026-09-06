package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public final class McpJsonRpcHandlerTest {
    private final FakeBackend mBackend = new FakeBackend();
    private final McpJsonRpcHandler mHandler =
            new McpJsonRpcHandler(mBackend);

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
    public void rejectsNonObjectArgumentsBeforeCallingBackend() throws Exception {
        for (final Object arguments : new Object[] {
                new JSONArray(), "ignored", 7, false, JSONObject.NULL}) {
            final McpJsonRpcResponse response = mHandler.handle(new JSONObject()
                    .put("jsonrpc", "2.0").put("id", 5)
                    .put("method", "tools/call")
                    .put("params", new JSONObject().put("name", "close_desktop")
                            .put("arguments", arguments)).toString());
            assertEquals(400, response.httpStatus);
            assertEquals(-32602, new JSONObject(response.body)
                    .getJSONObject("error").getInt("code"));
        }
        assertEquals(0, mBackend.calls);
    }

    @Test
    public void permitsOmittedArgumentsButRejectsExplicitNullParams() {
        final McpJsonRpcResponse omitted = mHandler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"example\"}}");
        assertEquals(200, omitted.httpStatus);
        assertEquals(1, mBackend.calls);
        final McpJsonRpcResponse invalid = mHandler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\",\"params\":null}");
        assertEquals(400, invalid.httpStatus);
        assertEquals(1, mBackend.calls);
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
        int calls;

        @Override
        public JSONArray listTools() throws JSONException {
            return new JSONArray().put(new JSONObject().put("name", "example"));
        }

        @Override
        public JSONObject callTool(
                final String name, final JSONObject arguments)
                throws JSONException {
            calls++;
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
