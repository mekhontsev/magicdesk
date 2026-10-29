package io.github.mekhontsev.magicdesk;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded argv and explicit environment; never rewrites application policy or command flags. */
final class LinuxApplicationLaunch {
    static JSONObject prepare(JSONObject args) throws JSONException {
        JSONArray supplied = args.getJSONArray("argv");
        if (supplied.length() < 1 || supplied.length() > 128) throw new IllegalArgumentException("argv must contain 1..128 strings");
        List<String> command = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < supplied.length(); ++i) {
            Object item = supplied.get(i);
            if (!(item instanceof String s) || s.indexOf('\0') >= 0 || (total += s.length()) > 65536)
                throw new IllegalArgumentException("Invalid Linux launch arguments");
            command.add(s);
        }
        if (command.get(0).isEmpty() || command.get(0).startsWith("-")) throw new IllegalArgumentException("Missing executable or application id");
        JSONArray argv = new JSONArray(), env = new JSONArray();
        command.forEach(argv::put);
        JSONObject environment = args.optJSONObject("environment");
        if (environment != null) {
            if (environment.length() > 32) throw new IllegalArgumentException("Too many environment overrides");
            for (var keys = environment.keys(); keys.hasNext();) {
                String key = keys.next();
                Object value = environment.get(key);
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]*") || !(value instanceof String text)
                        || text.indexOf('\0') >= 0 || (total += key.length() + text.length()) > 65536)
                    throw new IllegalArgumentException("Invalid environment override");
                env.put(key + "=" + value);
            }
        }
        return new JSONObject().put("argv", argv).put("environment", env);
    }
    private LinuxApplicationLaunch() { }
}
