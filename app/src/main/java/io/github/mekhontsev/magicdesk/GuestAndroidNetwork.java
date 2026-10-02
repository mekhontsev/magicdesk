package io.github.mekhontsev.magicdesk;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import java.io.IOException;

/** One-shot shell-side discovery; raw Linux DNS is not Android's Private DNS resolver. */
final class GuestAndroidNetwork {
    static String resolver() throws Exception {
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepare();
        // ConnectivityManager retains the application context, not a package wrapper.
        var context = FrameworkPrivilegedProcessApi.createShellContext(0);
        var manager = context.getSystemService(ConnectivityManager.class);
        var network = manager.getActiveNetwork();
        var properties = manager.getLinkProperties(network);
        var capabilities = manager.getNetworkCapabilities(network);
        if (properties == null || properties.getDnsServers().isEmpty())
            throw new IOException("No active-network DNS servers; choose --dns preserve or explicit addresses");
        if (properties.isPrivateDnsActive() || capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            throw new IOException("Private DNS/VPN cannot be copied into resolv.conf; choose --dns preserve or explicit addresses");
        return GuestDnsConfiguration.servers(properties.getDnsServers().stream()
                .limit(3).map(java.net.InetAddress::getHostAddress).toList());
    }

    private GuestAndroidNetwork() { }
}
