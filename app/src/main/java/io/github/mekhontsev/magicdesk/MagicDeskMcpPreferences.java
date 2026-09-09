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
    private static final String LOCAL_ACCESS = "local_access";
    private static final String NETWORK_ACCESS = "network_access";
    private static final String TOKEN = "token";
    private static final String NETWORK_ENABLED = "network_enabled";
    private static final String NETWORK_INTERFACE = "network_interface";
    private static final String NETWORK_PORT = "network_port";
    private static final String NETWORK_TOKEN = "network_token";

    private MagicDeskMcpPreferences() {
    }

    static boolean isEnabled(final Context context) {
        return preferences(context).getBoolean(ENABLED, false);
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
                new McpAccessPolicy(preferences.getStringSet(LOCAL_ACCESS, java.util.Set.of())),
                new McpAccessPolicy(preferences.getStringSet(NETWORK_ACCESS, java.util.Set.of())),
                token,
                preferences.getBoolean(NETWORK_ENABLED, false),
                preferences.getString(NETWORK_INTERFACE, ""),
                preferences.getInt(NETWORK_PORT, PORT),
                preferences.getString(NETWORK_TOKEN, ""));
    }

    static boolean setEnabled(
            final Context context, final boolean enabled) {
        final SharedPreferences.Editor editor = preferences(context).edit()
                .putBoolean(ENABLED, enabled);
        if (!enabled) {
            editor.remove(LOCAL_ACCESS);
            editor.remove(NETWORK_ACCESS);
            editor.putBoolean(NETWORK_ENABLED, false);
        }
        return editor.commit();
    }

    static boolean setAccess(final Context context, final boolean network,
            final java.util.Set<String> permissions) {
        return preferences(context).edit()
                .putStringSet(network ? NETWORK_ACCESS : LOCAL_ACCESS,
                        new McpAccessPolicy(permissions).names()).commit();
    }

    static boolean regenerateToken(final Context context) {
        return preferences(context).edit()
                .putString(TOKEN, newToken())
                .commit();
    }

    static boolean configureNetwork(final Context context, final String network,
            final int port) {
        if (network == null || network.isBlank() || port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Select an interface and port 1024-65535");
        }
        final SharedPreferences prefs = preferences(context);
        final SharedPreferences.Editor editor = prefs.edit()
                .putString(NETWORK_INTERFACE, network).putInt(NETWORK_PORT, port);
        if (prefs.getString(NETWORK_TOKEN, "").length() < 32) {
            editor.putString(NETWORK_TOKEN, newToken());
        }
        return editor.commit();
    }

    static boolean setNetworkEnabled(final Context context, final boolean enabled) {
        final Values values = load(context);
        if (enabled && (!values.enabled || values.networkInterface.isEmpty()
                || values.networkToken.length() < 32)) return false;
        return preferences(context).edit().putBoolean(NETWORK_ENABLED, enabled).commit();
    }

    static boolean regenerateNetworkToken(final Context context) {
        return preferences(context).edit().putString(NETWORK_TOKEN, newToken()).commit();
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
        final McpAccessPolicy localAccess;
        final McpAccessPolicy networkAccess;
        final String token;
        final boolean networkEnabled;
        final String networkInterface;
        final int networkPort;
        final String networkToken;

        Values(
                final boolean enabled,
                final McpAccessPolicy localAccess,
                final McpAccessPolicy networkAccess,
                final String token,
                final boolean networkEnabled,
                final String networkInterface,
                final int networkPort,
                final String networkToken) {
            this.enabled = enabled;
            this.localAccess = localAccess;
            this.networkAccess = networkAccess;
            this.token = token == null ? "" : token;
            this.networkEnabled = networkEnabled;
            this.networkInterface = networkInterface == null ? "" : networkInterface;
            this.networkPort = networkPort;
            this.networkToken = networkToken == null ? "" : networkToken;
        }

        String endpoint() {
            return "http://" + HOST + ':' + PORT + "/mcp";
        }
    }
}
