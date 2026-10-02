package io.github.mekhontsev.magicdesk;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;

/** Explicit resolver inputs, independent of distribution and Android network discovery. */
final class GuestDnsConfiguration {
    static String servers(List<String> values) {
        if (values.isEmpty() || values.size() > 3) throw new IllegalArgumentException("Specify 1-3 DNS server addresses");
        var servers = new LinkedHashSet<String>();
        for (String value : values) {
            if (value == null || value.isEmpty() || !value.matches("[0-9a-fA-F:.]+"))
                throw new IllegalArgumentException("DNS requires numeric IP addresses");
            try {
                if (!value.contains(":")) {
                    String[] parts = value.split("\\.", -1);
                    if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 address");
                    for (String part : parts) if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255)
                        throw new IllegalArgumentException("Invalid IPv4 address");
                }
                servers.add(InetAddress.getByName(value).getHostAddress());
            } catch (java.net.UnknownHostException error) { throw new IllegalArgumentException("Invalid DNS address", error); }
        }
        StringBuilder result = new StringBuilder("# Prepared by MagicDesk; managed by the guest after installation.\n");
        for (String server : servers) result.append("nameserver ").append(server).append('\n');
        return result.toString();
    }

    private GuestDnsConfiguration() { }
}
