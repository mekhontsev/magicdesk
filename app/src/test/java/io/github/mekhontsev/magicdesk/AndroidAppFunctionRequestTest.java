package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class AndroidAppFunctionRequestTest {
    @Test
    public void omittedParametersAreEmptyAndObjectPayloadIsPreserved() throws Exception {
        final JSONObject args = new JSONObject();
        final JSONObject empty = AndroidIntegrationGateway.optionalObject(args, "parameters");
        assertEquals(0, empty.length());
        assertNotSame(empty, AndroidIntegrationGateway.optionalObject(args, "parameters"));

        final JSONObject parameters = new JSONObject()
                .put("properties", new JSONObject().put("query", "  exact text  "));
        args.put("parameters", parameters);
        assertSame(parameters, AndroidIntegrationGateway.optionalObject(args, "parameters"));
        assertEquals("  exact text  ", parameters.getJSONObject("properties").getString("query"));
    }

    @Test
    public void presentNonObjectParametersAreRejected() throws Exception {
        for (final Object value : new Object[] {
                "{}", "text", new JSONArray(), JSONObject.NULL, true, 1}) {
            final JSONObject args = new JSONObject().put("parameters", value);
            final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> AndroidIntegrationGateway.optionalObject(args, "parameters"));
            assertEquals("parameters must be an object", error.getMessage());
        }
    }

    @Test
    public void malformedParametersNeverReachShellDispatch() throws Exception {
        RuntimeSourceFixture.verify("""
                static final int MAX_APP_FUNCTION_PARAMETERS_CHARS = 262_144;
                static final class JSONException extends Exception {}
                static final class JSONObject extends HashMap<String, Object> {
                    static final Object NULL = new Object();
                    boolean has(String key) { return containsKey(key); }
                    Object opt(String key) { return get(key); }
                    JSONObject optJSONObject(String key) {
                        return get(key) instanceof JSONObject ? (JSONObject) get(key) : null;
                    }
                    String optString(String key, String fallback) {
                        return get(key) instanceof String ? (String) get(key) : fallback;
                    }
                    long optLong(String key, long fallback) {
                        return get(key) instanceof Number ? ((Number) get(key)).longValue() : fallback;
                    }
                }
                static final class JSONArray {}
                static final class DesktopAutomationResult {
                    static DesktopAutomationResult failure(String code, String message, boolean retry) {
                        return new DesktopAutomationResult();
                    }
                }
                static final class DesktopAutomationErrorCode {
                    static final String SHELL_UNAVAILABLE = "shell_unavailable";
                }
                static final class ShellAccess {
                    static int dispatched;
                    static String parameters;
                    static boolean isReady() { return true; }
                    static String executeAppFunction(String pkg, String id, String encoded, long timeout) {
                        dispatched++;
                        parameters = encoded;
                        return "response";
                    }
                }
                static DesktopAutomationResult shellGatewayResult(String encoded) {
                    return new DesktopAutomationResult();
                }
                public static void verify() throws Exception {
                    Fixture gateway = new Fixture();
                    JSONObject args = new JSONObject();
                    args.put("package", "com.example.app");
                    args.put("functionId", "example.function");
                    for (Object value : new Object[] {"{}", "text", new JSONArray(), JSONObject.NULL, true, 1}) {
                        args.put("parameters", value);
                        try {
                            gateway.executeAppFunction(args);
                            throw new AssertionError("malformed parameters executed defaults");
                        } catch (IllegalArgumentException expected) {
                            check("parameters must be an object".equals(expected.getMessage()),
                                    "wrong validation boundary");
                        }
                        check(ShellAccess.dispatched == 0, "malformed parameters reached shell dispatch");
                    }
                    args.remove("parameters");
                    gateway.executeAppFunction(args);
                    check(ShellAccess.dispatched == 1, "omitted parameters did not dispatch");
                    check("{}".equals(ShellAccess.parameters), "omitted parameters were not empty");
                    JSONObject parameters = new JSONObject();
                    parameters.put("query", "exact text");
                    args.put("parameters", parameters);
                    gateway.executeAppFunction(args);
                    check(ShellAccess.dispatched == 2, "object parameters did not dispatch");
                    check(parameters.toString().equals(ShellAccess.parameters), "payload was replaced");
                }
                """ + RuntimeSourceFixture.methods("AndroidIntegrationGateway",
                "executeAppFunction", "optionalObject", "requiredString", "optionalString", "timeoutMillis"));
    }
}
