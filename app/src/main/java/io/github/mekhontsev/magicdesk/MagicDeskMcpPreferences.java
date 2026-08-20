package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Base64;

/** Private MCP transport settings; the bearer token never enters diagnostics. */
final class MagicDeskMcpPreferences {
    static final String HOST = "127.0.0.1";
    static final int PORT = 8765;

    private static final String PREFERENCES = "magicdesk_mcp";
    private static final String ENABLED = "enabled";
    private static final String DEVELOPER_TOOLS = "developer_tools";
    private static final String TOKEN = "token";

    private MagicDeskMcpPreferences() {
    }

    static Values load(final Context context) {
        final SharedPreferences preferences = preferences(context);
        String token = preferences.getString(TOKEN, "");
        if (token == null || token.length() < 32) {
            token = newToken();
            if (!preferences.edit().putString(TOKEN, token).commit()) {
                token = "";
            }
        }
        return new Values(
                preferences.getBoolean(ENABLED, false),
                preferences.getBoolean(DEVELOPER_TOOLS, false),
                token);
    }

    static boolean setEnabled(
            final Context context, final boolean enabled) {
        final SharedPreferences.Editor editor = preferences(context).edit()
                .putBoolean(ENABLED, enabled);
        if (!enabled) {
            editor.putBoolean(DEVELOPER_TOOLS, false);
        }
        return editor.commit();
    }

    static boolean setDeveloperTools(
            final Context context, final boolean enabled) {
        return preferences(context).edit()
                .putBoolean(DEVELOPER_TOOLS, enabled)
                .commit();
    }

    static boolean regenerateToken(final Context context) {
        return preferences(context).edit()
                .putString(TOKEN, newToken())
                .commit();
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String newToken() {
        final byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static final class Values {
        final boolean enabled;
        final boolean developerTools;
        final String token;

        Values(
                final boolean enabled,
                final boolean developerTools,
                final String token) {
            this.enabled = enabled;
            this.developerTools = developerTools;
            this.token = token == null ? "" : token;
        }

        String endpoint() {
            return "http://" + HOST + ':' + PORT + "/mcp";
        }
    }
}
