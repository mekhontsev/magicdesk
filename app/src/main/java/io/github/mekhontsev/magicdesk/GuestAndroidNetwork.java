package io.github.mekhontsev.magicdesk;

import android.net.ConnectivityManager;
import android.content.Context;
import java.io.IOException;
import java.util.List;

/** One-shot caller-scoped discovery; raw Linux DNS is not Android's Private DNS resolver. */
final class GuestAndroidNetwork {
    static String resolver() throws Exception {
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepare();
        // ConnectivityManager retains the application context, not a package wrapper.
        var context = FrameworkPrivilegedProcessApi.createShellContext(0);
        return GuestDnsConfiguration.servers(servers(context));
    }

    static List<String> servers(Context context) throws IOException {
        var manager = context.getSystemService(ConnectivityManager.class);
        var network = manager.getActiveNetwork();
        var properties = manager.getLinkProperties(network);
        if (properties == null || properties.getDnsServers().isEmpty())
            throw new IOException("No active-network DNS servers; choose --dns preserve or explicit addresses");
        if (properties.isPrivateDnsActive())
            throw new IOException("Android Private DNS is active; copying server addresses would not preserve its encrypted DNS policy");
        // Android selects the caller's network, including any VPN covering its UID.
        // Taking this snapshot does not bind future guest sockets to that network.
        return properties.getDnsServers().stream()
                .limit(3).map(java.net.InetAddress::getHostAddress).toList();
    }

    private GuestAndroidNetwork() { }
}
