package io.github.mekhontsev.magicdesk;

import java.util.List;

/** Explicit guest addresses and per-launch authentication; no host filesystem aliases. */
final class GuestGraphicalConnection {
    static final String WAYLAND_PATH = LinuxConnectionContract.WAYLAND_PATH;

    static GuestLaunchPlan plan(GuestEnvironment environment, String directory, String command,
            GraphicalProtocol protocol) {
        return new GuestLaunchPlan(environment, directory, LinuxGraphicalEnvironment.arguments(
                "/tmp/magicdesk-helpers/libmagicdesk_guest_files.so", protocol, true, true,
                List.of("/bin/sh", "-lc", command)));
    }

    static String invocation(GuestLaunchPlan plan, GraphicalProtocol protocol) {
        return invocation(plan, protocol, false);
    }

    static String invocation(GuestLaunchPlan plan, GraphicalProtocol protocol, boolean files) {
        return invocation(plan, protocol, files, GuestSessionOptions.DIRECT);
    }

    static String invocation(GuestLaunchPlan plan, GraphicalProtocol protocol, boolean files, GuestSessionOptions options) {
        String endpoint = "\"${MAGICDESK_GRAPHICS_ENDPOINT:?Missing graphical endpoint}\"";
        String source = protocol == GraphicalProtocol.WAYLAND ? ShellCommandLine.quote(WAYLAND_PATH)
                : "\"/tmp/.X11-unix/X${DISPLAY#:}\"";
        String fileOptions = files ? " --bind-ro \"${MAGICDESK_GUEST_LIBRARIES:?Missing helpers}\" /tmp/magicdesk-helpers"
                + " --env \"MAGICDESK_AUTOMATION_SOCKET=${MAGICDESK_AUTOMATION_SOCKET:-}\""
                + " --env \"MAGICDESK_AUTOMATION_TOKEN=${MAGICDESK_AUTOMATION_TOKEN:-}\""
                + " --env MAGICDESK_AUTOMATION_HELPER=/tmp/magicdesk-helpers/libmagicdesk_linux_settings.so"
                + " --env \"MAGICDESK_GUEST_FILES_SOCKET=$MAGICDESK_GUEST_FILES_SOCKET\""
                + " --env \"MAGICDESK_GUEST_FILES_TOKEN=$MAGICDESK_GUEST_FILES_TOKEN\"" : "";
        String attachments = fileOptions
                + (protocol == GraphicalProtocol.X11 ? " --socket-resource-path " : " --socket-path ") + source + " " + endpoint
                + (protocol == GraphicalProtocol.X11 ? " --socket-resource-abstract " + source + " " + endpoint : "");
        return "set -eu; " + options.invocation(plan, GuestLaunchPlan.Cli.MAGICDESK, attachments,
                files ? "mkdir -p /tmp/magicdesk-helpers" : "");
    }
    private GuestGraphicalConnection() { }
}
