package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/** Semantic UI dispatch is independent of MCP, Desktop and the graphical display protocol. */
final class UiAutomation implements AutoCloseable {
    private final AndroidUiAutomation android;
    private final LinuxUiAutomation linux = new LinuxUiAutomation();
    UiAutomation(Context context) { android = new AndroidUiAutomation(context); }
    JSONObject execute(String operation, JSONObject args) throws IOException, JSONException {
        boolean linuxTarget = operation.startsWith("ui.")
                && (args.has("sessionId") || LinuxUiAutomation.handles(args.optString("elementId", "")));
        return linuxTarget ? linux.execute(operation, args) : android.execute(operation, args);
    }
    JSONObject awakeState() throws JSONException { return android.awakeState(); }
    @Override public void close() { linux.close(); android.close(); }
}
