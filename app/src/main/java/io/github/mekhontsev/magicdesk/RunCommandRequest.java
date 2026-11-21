package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Versioned external contract; arguments are data, never an implicit shell program. */
record RunCommandRequest(String backend, int executorUid, String termuxPackage, String environmentId,
        String program, List<String> arguments, Map<String, String> environment, String directory,
        String stdin, String mode, int displayId, int timeoutMillis, String keyboardDirectory) {
    static final int MAX_REQUEST_BYTES = 65536;
    static final int MAX_OUTPUT_BYTES = 16384;
    private static final Set<String> FIELDS = Set.of("version", "backend", "executorUid", "termuxPackage",
            "environmentId", "program", "arguments", "environment", "cwd", "stdin", "mode", "displayId", "timeoutMillis", "keyboardDirectory");

    static RunCommandRequest parse(String source) throws Exception {
        if (source == null || source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES)
            throw new IllegalArgumentException("Missing or oversized request");
        var json = new JSONObject(source);
        for (var keys = json.keys(); keys.hasNext();) {
            String key = keys.next();
            if (!FIELDS.contains(key)) throw new IllegalArgumentException("Unknown request field: " + key);
        }
        if (integer(json, "version", -1) != 1) throw new IllegalArgumentException("Unsupported request version");
        String backend = string(json, "backend", "");
        if (!Set.of("app", "shell", "termux", "shroot").contains(backend)) throw new IllegalArgumentException("Unknown backend");
        int uid = integer(json, "executorUid", -1);
        if (backend.equals("shroot")) GuestLaunchPlan.requireIdentity(uid);
        else if (backend.equals("app")) {
            if (uid < 10000) throw new IllegalArgumentException("executorUid must select the MagicDesk app UID");
            GuestLaunchPlan.requireIdentity(uid);
        } else if (backend.equals("shell") && uid != 0 && uid != 2000)
            throw new IllegalArgumentException("executorUid must explicitly select 2000 or 0");
        String termux = string(json, "termuxPackage", ""), guest = string(json, "environmentId", "");
        if (backend.equals("termux")) {
            if (!termux.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+") || uid != -1)
                throw new IllegalArgumentException("Termux requires a package, not executorUid");
        } else if (!termux.isEmpty()) throw new IllegalArgumentException("termuxPackage requires Termux");
        if (backend.equals("shroot")) {
            if (!java.util.UUID.fromString(guest).toString().equals(guest)) throw new IllegalArgumentException("Invalid environmentId");
        } else if (!guest.isEmpty()) throw new IllegalArgumentException("environmentId requires Shroot");
        String program = absolute(string(json, "program", ""), "program");
        var args = new ArrayList<String>();
        if (json.has("arguments")) {
            var array = json.getJSONArray("arguments");
            if (array.length() > 256) throw new IllegalArgumentException("Too many arguments");
            for (int i = 0; i < array.length(); i++) args.add(text(array.get(i), "argument"));
        }
        var env = new LinkedHashMap<String, String>();
        if (json.has("environment")) {
            var values = json.getJSONObject("environment");
            if (values.length() > 128) throw new IllegalArgumentException("Too many environment variables");
            for (var keys = values.keys(); keys.hasNext();) {
                String key = keys.next();
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid environment variable");
                env.put(key, text(values.get(key), key));
            }
        }
        String cwd = string(json, "cwd", "");
        if (!cwd.isEmpty()) absolute(cwd, "cwd");
        String stdin = json.has("stdin") ? string(json, "stdin", "") : null;
        String mode = string(json, "mode", "background");
        if (!Set.of("background", "terminal", "x11", "wayland").contains(mode)) throw new IllegalArgumentException("Unknown mode");
        int display = integer(json, "displayId", -1);
        if (display < -1 || mode.equals("background") && display != -1)
            throw new IllegalArgumentException("displayId requires an interactive mode");
        if (!mode.equals("background") && stdin != null) throw new IllegalArgumentException("stdin requires background mode");
        String keyboard = string(json, "keyboardDirectory", "");
        if (!keyboard.isEmpty()) {
            absolute(keyboard, "keyboardDirectory");
            if (backend.equals("shroot") || !mode.equals("x11") && !mode.equals("wayland"))
                throw new IllegalArgumentException("keyboardDirectory requires native shell/Termux graphics; Shroot supplies its own");
        }
        int timeout = integer(json, "timeoutMillis", 60000);
        if (timeout < 1000 || timeout > 86400000) throw new IllegalArgumentException("timeoutMillis must be 1000..86400000");
        return new RunCommandRequest(backend, uid, termux, guest, program, List.copyOf(args), Map.copyOf(env), cwd, stdin, mode, display, timeout, keyboard);
    }

    String script() {
        StringBuilder result = new StringBuilder();
        environment.forEach((key, value) -> result.append("export ").append(key).append('=')
                .append(ShellCommandLine.quote(value)).append('\n'));
        result.append("exec ").append(ShellCommandLine.quote(program));
        for (String argument : arguments) result.append(' ').append(ShellCommandLine.quote(argument));
        return result.toString();
    }

    private static int integer(JSONObject json, String key, int fallback) throws Exception {
        if (!json.has(key)) return fallback;
        Object value = json.get(key);
        if (!(value instanceof Integer)) throw new IllegalArgumentException(key + " must be an integer");
        return (Integer) value;
    }
    private static String string(JSONObject json, String key, String fallback) throws Exception {
        return json.has(key) ? text(json.get(key), key) : fallback;
    }
    private static String text(Object value, String key) {
        if (!(value instanceof String result) || result.indexOf('\0') >= 0)
            throw new IllegalArgumentException(key + " must be a NUL-free string");
        return result;
    }
    private static String absolute(String value, String key) {
        if (!value.startsWith("/")) throw new IllegalArgumentException(key + " must be absolute");
        return value;
    }
}
