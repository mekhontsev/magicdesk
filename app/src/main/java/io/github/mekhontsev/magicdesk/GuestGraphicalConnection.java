package io.github.mekhontsev.magicdesk;

import java.util.List;

/** Explicit guest addresses and per-launch authentication; no host filesystem aliases. */
final class GuestGraphicalConnection {
    static final String WAYLAND_PATH = "/tmp/magicdesk-wayland/wayland-0";

    static String invocation(GuestLaunchPlan plan, GraphicalProtocol protocol) {
        String endpoint = "\"${MAGICDESK_GRAPHICS_ENDPOINT:?Missing graphical endpoint}\"";
        String source = protocol == GraphicalProtocol.WAYLAND ? ShellCommandLine.quote(WAYLAND_PATH)
                : "\"/tmp/.X11-unix/X${DISPLAY#:}\"";
        return "set -eu; exec " + quoted(plan.launcherArguments()) + " --socket-path " + source + " " + endpoint
                + (protocol == GraphicalProtocol.X11 ? " --socket-abstract " + source + " " + endpoint : "")
                + " -- " + quoted(plan.command());
    }

    static String client(GraphicalProtocol protocol, String command) {
        if (protocol != GraphicalProtocol.X11) return command;
        return "set -eu; export XAUTHORITY=\"$XDG_RUNTIME_DIR/Xauthority\"; "
                + "printf '%s' \"${MAGICDESK_X11_AUTHORITY:?Missing X11 authority}\" | base64 -d > \"$XAUTHORITY\"; "
                + "unset MAGICDESK_X11_AUTHORITY; " + command;
    }

    private static String quoted(List<String> arguments) {
        return String.join(" ", arguments.stream().map(ShellCommandLine::quote).toList());
    }
    private GuestGraphicalConnection() { }
}
