package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public class GuestAndroidNetworkTest {
    @Test public void snapshotsCallerNetworkWithoutRejectingVpnOrBindingIt() throws Exception {
        RuntimeSourceFixture.verify("""
                static class android { static class os { static class Looper {
                    static Object myLooper() { return new Object(); }
                    static void prepare() { }
                } } }
                static class FrameworkPrivilegedProcessApi {
                    static Context createShellContext(int user) {
                        check(user == 0, "changed executor context");
                        return new Context();
                    }
                }
                static class Context {
                    ConnectivityManager getSystemService(Class<?> type) { return manager; }
                }
                static class NetworkCapabilities {
                    static final int TRANSPORT_VPN = 4;
                    boolean hasTransport(int type) { return type == TRANSPORT_VPN; }
                }
                static class Properties {
                    boolean privateDns;
                    List<java.net.InetAddress> servers = List.of();
                    boolean isPrivateDnsActive() { return privateDns; }
                    List<java.net.InetAddress> getDnsServers() { return servers; }
                }
                static class ConnectivityManager {
                    Object active = new Object();
                    Properties properties;
                    Object getActiveNetwork() { return active; }
                    Properties getLinkProperties(Object network) {
                        check(network == active, "did not use caller's active network");
                        return properties;
                    }
                    NetworkCapabilities getNetworkCapabilities(Object network) {
                        return new NetworkCapabilities();
                    }
                }
                static class GuestDnsConfiguration {
                    static String servers(List<String> values) { return String.join(",", values); }
                }
                static ConnectivityManager manager = new ConnectivityManager();
                public static void verify() throws Exception {
                    expectFailure("No active-network");
                    manager.properties = new Properties();
                    expectFailure("No active-network");
                    manager.properties.servers = List.of(java.net.InetAddress.getByName("192.0.2.1"));
                    check(resolver().equals("192.0.2.1"), "VPN DNS rejected");
                    manager.active = new Object();
                    manager.properties.servers = List.of(java.net.InetAddress.getByName("192.0.2.2"));
                    check(resolver().equals("192.0.2.2"), "stale network cached");
                    check(servers(new Context()).equals(List.of("192.0.2.2")), "app context changed DNS selection");
                    manager.properties.privateDns = true;
                    expectFailure("Android Private DNS");
                }
                static void expectFailure(String message) throws Exception {
                    try { resolver(); throw new AssertionError("missing DNS failure"); }
                    catch (IOException error) { check(error.getMessage().contains(message), error.toString()); }
                }
                """ + RuntimeSourceFixture.methods("GuestAndroidNetwork", "resolver", "servers"));
    }
}
