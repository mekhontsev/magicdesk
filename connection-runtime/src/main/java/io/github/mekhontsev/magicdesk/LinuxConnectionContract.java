package io.github.mekhontsev.magicdesk;

import java.util.List;
import java.util.Map;

/** Optional protocol endpoints, never a command channel or a host filesystem grant. */
public final class LinuxConnectionContract {
    public static final String ACTION = "io.github.mekhontsev.magicdesk.CONNECT_LINUX";
    public static final int FORMAT = 1;
    public static final String PIPEWIRE_PATH = "/tmp/magicdesk-audio/pipewire";
    public static final String PULSE_PATH = "/tmp/magicdesk-audio/pulse";
    public static final String WAYLAND_PATH = "/tmp/magicdesk-wayland/wayland-0";

    public static List<String> launchArguments(int format, Map<String, String> endpoints) {
        return launchArguments(format, endpoints, null);
    }

    public static List<String> launchArguments(int format, Map<String, String> endpoints, String authorityDirectory) {
        if (format != FORMAT) throw new IllegalArgumentException("Unsupported Linux connection format");
        String nativeEndpoint = endpoint(endpoints.get("pipewire"));
        String pulseEndpoint = endpoint(endpoints.get("pulse"));
        if (nativeEndpoint.equals(pulseEndpoint)) throw new IllegalArgumentException("Duplicate audio endpoints");
        var arguments = new java.util.ArrayList<>(List.of("--socket-path", PIPEWIRE_PATH, nativeEndpoint,
                "--socket-path", PULSE_PATH, pulseEndpoint,
                "--env", "PIPEWIRE_REMOTE=" + PIPEWIRE_PATH,
                "--env", "PULSE_SERVER=unix:" + PULSE_PATH));
        String protocol = endpoints.get("graphicsProtocol");
        if (protocol != null) {
            String graphics = endpoint(endpoints.get("graphicsEndpoint"));
            switch (protocol) {
                case "wayland" -> arguments.addAll(List.of("--socket-path", WAYLAND_PATH, graphics,
                        "--env", "WAYLAND_DISPLAY=" + WAYLAND_PATH));
                case "x11" -> {
                    String display = endpoints.get("display");
                    if (display == null || !display.matches("[0-9]{1,5}") || Integer.parseInt(display) > 65535)
                        throw new IllegalArgumentException("Invalid X11 display");
                    if (authorityDirectory == null || !authorityDirectory.startsWith("/"))
                        throw new IllegalArgumentException("Private Xauthority directory is required");
                    String socket = "/tmp/.X11-unix/X" + display;
                    arguments.addAll(List.of("--socket-resource-path", socket, graphics,
                            "--socket-resource-abstract", socket, graphics,
                            "--bind-ro-create", authorityDirectory, "/tmp/magicdesk-xauthority",
                            "--env", "DISPLAY=:" + display,
                            "--env", "XAUTHORITY=/tmp/magicdesk-xauthority/Xauthority"));
                }
                default -> throw new IllegalArgumentException("Unsupported graphics protocol");
            }
        }
        return List.copyOf(arguments);
    }

    private static String endpoint(String value) {
        if (value == null || !value.matches("magicdesk-[a-z0-9-]{16,80}"))
            throw new IllegalArgumentException("Invalid Linux connection endpoint");
        return value;
    }

    private LinuxConnectionContract() { }
}
