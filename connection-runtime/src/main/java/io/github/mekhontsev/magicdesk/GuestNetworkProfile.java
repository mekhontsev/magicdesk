package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Per-launch arguments only. Native Shroot owns parsing, preparation and lifetime. */
public record GuestNetworkProfile(Mode mode, List<String> publications, List<String> dns, Addressing addressing) {
    public enum Mode { HOST, NONE, BRIDGE, ROUTED }
    public record Addressing(List<String> subnets, String ip, String ip6, List<String> gateways) {
        public static final Addressing DEFAULT = new Addressing(List.of(), "", "", List.of());
        public Addressing {
            subnets = List.copyOf(subnets);
            gateways = List.copyOf(gateways);
            Objects.requireNonNull(ip);
            Objects.requireNonNull(ip6);
            if (subnets.size() > 2 || gateways.size() > 2) throw new IllegalArgumentException("Too many address families");
            for (var values : List.of(subnets, gateways)) for (var value : values) validateArgument(value);
            if (!ip.isEmpty()) validateArgument(ip);
            if (!ip6.isEmpty()) validateArgument(ip6);
        }
        private void appendTo(List<String> out) {
            for (var subnet : subnets) out.addAll(List.of("--subnet", subnet));
            if (!ip.isEmpty()) out.addAll(List.of("--ip", ip));
            if (!ip6.isEmpty()) out.addAll(List.of("--ip6", ip6));
            for (var gateway : gateways) out.addAll(List.of("--gateway", gateway));
        }
    }
    public static final GuestNetworkProfile HOST = new GuestNetworkProfile(Mode.HOST, List.of(), List.of());

    public GuestNetworkProfile(Mode mode, List<String> publications, List<String> dns) {
        this(mode, publications, dns, Addressing.DEFAULT);
    }

    public GuestNetworkProfile {
        Objects.requireNonNull(mode, "network mode");
        Objects.requireNonNull(addressing, "network addressing");
        publications = List.copyOf(publications);
        dns = List.copyOf(dns);
        if (publications.size() > MAX_PORTS || dns.size() > 3
                || (!publications.isEmpty() || !addressing.equals(Addressing.DEFAULT)) && mode != Mode.BRIDGE
                || !dns.isEmpty() && mode == Mode.NONE)
            throw new IllegalArgumentException("Incompatible network options");
        for (var values : List.of(publications, dns)) for (String value : values) validateArgument(value);
    }

    private static void validateArgument(String value) {
        if (value.isEmpty() || value.length() > 128 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid network argument");
    }

    public static final int MAX_PORTS = 128;

    /** The settings UI deliberately exposes only IPv4 loopback or all IPv4 interfaces. */
    public record Port(int host, int guest, boolean udp, boolean external) {
        public Port {
            if (host<1 || host>65535 || guest<1 || guest>65535)
                throw new IllegalArgumentException("Ports must be between 1 and 65535");
        }
        public String argument() {
            return (external ? "0.0.0.0:" : "127.0.0.1:")+host+":"+guest+(udp ? "/udp" : "/tcp");
        }
        public static Port parse(String argument) {
            var parts=argument.split("[:/]",-1);
            if (parts.length!=4) throw new IllegalArgumentException("Invalid saved port");
            var port=new Port(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),
                    parts[3].equals("udp"),parts[0].equals("0.0.0.0"));
            if (!argument.equals(port.argument())) throw new IllegalArgumentException("Invalid saved port");
            return port;
        }
    }

    public static GuestNetworkProfile forSettings(Mode mode,List<Port> ports) {
        if (mode==Mode.ROUTED) throw new IllegalArgumentException("Unsupported network mode");
        var hosts=new java.util.HashSet<Integer>();
        for (var p:ports) if (!hosts.add(p.host()+(p.udp() ? 65536 : 0)))
            throw new IllegalArgumentException("Duplicate host port: "+p.host()+(p.udp() ? "/udp" : "/tcp"));
        return new GuestNetworkProfile(mode,ports.stream().map(Port::argument).toList(),List.of());
    }

    /** Line records, not shell code. A missing setting means host; an incomplete one never does. */
    public String settings() {
        if (mode==Mode.ROUTED || !dns.isEmpty() || !addressing.equals(Addressing.DEFAULT))
            throw new IllegalArgumentException("Not a settings profile");
        return mode.name().toLowerCase(java.util.Locale.ROOT)+"\n"
                +(publications.isEmpty() ? "" : String.join("\n",publications)+"\n")+"end\n";
    }
    public static GuestNetworkProfile fromSettings(String value) {
        if (value==null) return HOST;
        var lines=value.lines().toList();
        if (lines.size()<2 || lines.size()>MAX_PORTS+2 || !lines.get(lines.size()-1).equals("end"))
            throw new IllegalArgumentException("Incomplete network settings");
        return forSettings(Mode.valueOf(lines.get(0).toUpperCase(java.util.Locale.ROOT)),
                lines.subList(1,lines.size()-1).stream().map(Port::parse).toList());
    }

    public List<String> arguments() {
        if (equals(HOST)) return List.of();
        var result = new ArrayList<String>();
        result.add("--network");
        result.add(mode.name().toLowerCase(java.util.Locale.ROOT));
        for (String port : publications) result.addAll(List.of("-p", port));
        for (String server : dns) result.addAll(List.of("--dns", server));
        addressing.appendTo(result);
        return List.copyOf(result);
    }
}
