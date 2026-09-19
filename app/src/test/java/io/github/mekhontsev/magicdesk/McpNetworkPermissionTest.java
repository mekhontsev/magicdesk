package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class McpNetworkPermissionTest {
    @Test public void onlyConfirmedNetworkConfigurationRequestsPermission() throws Exception {
        RuntimeSourceFixture.verify("""
                static class android {
                    static class os { static class Build { static class VERSION { static int SDK_INT = 37; } } }
                    static class Manifest { static class permission { static String ACCESS_LOCAL_NETWORK = "lan"; } }
                }
                static final int LOCAL_NETWORK_PERMISSION_REQUEST = 1;
                static class MagicDeskMcpPreferences {
                    static boolean networkEnabled;
                    static MagicDeskMcpPreferences load(Object activity) { return new MagicDeskMcpPreferences(); }
                }
                static class RuntimeCapabilities {
                    static boolean allowed;
                    static boolean canAccessLocalNetwork(Object activity) { return allowed; }
                }
                int requests, saves;
                void saveSetting(boolean saved) { saves++; }
                void requestPermissions(String[] permissions, int request) {
                    check(permissions.length == 1 && permissions[0].equals("lan")
                            && request == LOCAL_NETWORK_PERMISSION_REQUEST, "exact permission");
                    requests++;
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    f.saveMcpNetworkSetting(true);
                    check(f.requests == 0, "disabled configuration does not request access");
                    MagicDeskMcpPreferences.networkEnabled = true;
                    f.saveMcpNetworkSetting(false);
                    check(f.requests == 0, "failed save does not request access");
                    android.os.Build.VERSION.SDK_INT = 36;
                    f.saveMcpNetworkSetting(true);
                    check(f.requests == 0, "no permission dialog on older releases");
                    android.os.Build.VERSION.SDK_INT = 37;
                    RuntimeCapabilities.allowed = true;
                    f.saveMcpNetworkSetting(true);
                    check(f.requests == 0, "already granted");
                    RuntimeCapabilities.allowed = false;
                    f.saveMcpNetworkSetting(true);
                    check(f.requests == 1 && f.saves == 5, "explicit enable requests once");
                }
                """ + RuntimeSourceFixture.methods("SettingsActivity", "saveMcpNetworkSetting"));
    }

    @Test public void permissionOnlyGatesNetworkAndRevocationStopsExistingListener() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Context { String getString(int id) { return "local network permission required"; } }
                static class R { static class string { static int settings_mcp_network_permission_required; } }
                static class RuntimeCapabilities { static boolean allowed;
                    static boolean canAccessLocalNetwork(Context context) { return allowed; } }
                static class MagicDeskMcpPreferences {
                    static final String HOST = "127.0.0.1"; static final int PORT = 8765;
                    static final Values values = new Values();
                    static Values load(Context c) { return values; }
                    static class Values { boolean enabled = true, networkEnabled;
                        String token = "local", networkToken = "network", networkInterface = "wifi";
                        int networkPort = 8766; }
                }
                static class MagicDeskMcpBackend { MagicDeskMcpBackend(Context c) { }
                    Object scoped(boolean network) { return this; } }
                static class McpJsonRpcHandler { McpJsonRpcHandler(Object backend) { } }
                static class MagicDeskMcpHttpServer {
                    boolean closed; final java.util.function.Supplier<String> token;
                    MagicDeskMcpHttpServer(McpJsonRpcHandler h, java.util.function.Supplier<String> t) { token = t; }
                    void start(String host, int port) throws IOException { }
                    void startNetwork(String address, int port) throws IOException { }
                    void close() { closed = true; }
                }
                static class McpNetworkInterfaces {
                    static int lookups;
                    static Binding find(String name) { lookups++; return new Binding(); }
                    static class Binding { String address = "192.168.1.2";
                        String endpoint(int port) { return address + ":" + port; } }
                }
                static class ShellAccess { static String usefulMessage(Throwable t) { return t.toString(); } }
                static Fixture sActive;
                final Context mContext = new Context(); boolean mClosed, observing;
                MagicDeskMcpPreferences.Values mSettings;
                MagicDeskMcpBackend mBackend;
                McpJsonRpcHandler mHandler, mNetworkHandler;
                MagicDeskMcpHttpServer mLocal, mNetwork;
                String mLocalError = "", mNetworkError = "", mNetworkEndpoint = "";
                void observeNetwork() { observing = true; }
                void unobserveNetwork() { observing = false; }
                public static void verify() throws Exception {
                    Fixture f = new Fixture(); f.reconcile();
                    var local = f.mLocal;
                    check(local != null && f.mNetwork == null, "local without grant");
                    MagicDeskMcpPreferences.values.networkEnabled = true;
                    f.reconcile();
                    check(f.mLocal == local && f.mNetwork == null && !f.observing, "denied network only");
                    check(McpNetworkInterfaces.lookups == 0 && !f.mNetworkError.isEmpty(), "denied before bind");
                    RuntimeCapabilities.allowed = true; f.reconcile();
                    var network = f.mNetwork;
                    check(network != null && f.mNetworkError.isEmpty(), "grant starts network");
                    f.reconcile(); check(f.mNetwork == network, "same binding retained");
                    RuntimeCapabilities.allowed = false;
                    check(network.token.get().isEmpty() && local.token.get().equals("local"), "revoked authentication");
                    f.reconcile();
                    check(network.closed && f.mNetwork == null && !f.observing, "revoked listener closed");
                    check(f.mLocal == local && !local.closed, "local unaffected");
                    RuntimeCapabilities.allowed = true; f.reconcile();
                    check(f.mNetwork != null, "regrant resumes saved configuration");
                    MagicDeskMcpPreferences.values.networkEnabled = false; f.reconcile();
                    check(f.mNetwork == null && f.mNetworkError.isEmpty(), "disabled independent of permission");
                }
                """ + RuntimeSourceFixture.methods("MagicDeskMcpRuntime", "reconcile", "stop", "stopNetwork"));
    }

    @Test public void olderReleasesNeverQueryNewPermission() throws Exception {
        RuntimeSourceFixture.verify("""
                static class android {
                    static class os { static class Build { static class VERSION { static int SDK_INT; } } }
                    static class Manifest { static class permission { static String ACCESS_LOCAL_NETWORK = "lan"; } }
                    static class content {
                        static class pm { static class PackageManager { static int PERMISSION_GRANTED = 0; } }
                        static class Context { int queries; int grant = -1;
                            int checkSelfPermission(String name) { queries++; return grant; } }
                    }
                }
                public static void verify() {
                    var context = new android.content.Context();
                    for (int sdk = 34; sdk <= 36; sdk++) {
                        android.os.Build.VERSION.SDK_INT = sdk;
                        check(canAccessLocalNetwork(context), "older release allowed");
                    }
                    check(context.queries == 0, "no new permission on old releases");
                    android.os.Build.VERSION.SDK_INT = 37;
                    check(!canAccessLocalNetwork(context), "permission required");
                    context.grant = 0;
                    check(canAccessLocalNetwork(context), "granted");
                }
                """ + RuntimeSourceFixture.methods("RuntimeCapabilities", "canAccessLocalNetwork"));
    }
}
