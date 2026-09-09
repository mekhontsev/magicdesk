package io.github.mekhontsev.magicdesk;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Explicit private IPv4 interfaces, including Wi-Fi, tethering and VPN. No DNS. */
final class McpNetworkInterfaces {
    static List<Binding> available() throws SocketException {
        final List<Binding> bindings = new ArrayList<>();
        final var interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return bindings;
        for (NetworkInterface network : Collections.list(interfaces)) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (isAllowed(address)) bindings.add(new Binding(network.getName(), address));
            }
        }
        bindings.sort(Comparator.comparing((Binding b) -> b.name)
                .thenComparing(b -> b.address.getHostAddress()));
        // Settings select an interface, not a particular DHCP address on it.
        final java.util.LinkedHashMap<String, Binding> interfacesByName = new java.util.LinkedHashMap<>();
        for (Binding binding : bindings) interfacesByName.putIfAbsent(binding.name, binding);
        return new ArrayList<>(interfacesByName.values());
    }

    static Binding find(final String name) throws SocketException {
        for (Binding binding : available()) {
            if (binding.name.equals(name)) return binding;
        }
        return null;
    }

    static boolean isAllowed(final InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        final byte[] bytes = address.getAddress();
        // Shared address space is also commonly assigned to authenticated VPN interfaces.
        final boolean shared = (bytes[0] & 255) == 100 && ((bytes[1] & 255) & 192) == 64;
        return (address.isSiteLocalAddress() || shared)
                && !address.isAnyLocalAddress() && !address.isLoopbackAddress()
                && !address.isMulticastAddress();
    }

    static final class Binding {
        final String name;
        final InetAddress address;

        Binding(final String name, final InetAddress address) {
            this.name = name;
            this.address = address;
        }

        String endpoint(final int port) {
            return "http://" + address.getHostAddress() + ":" + port + "/mcp";
        }

        @Override public String toString() {
            return name + " (" + address.getHostAddress() + ")";
        }
    }
}
