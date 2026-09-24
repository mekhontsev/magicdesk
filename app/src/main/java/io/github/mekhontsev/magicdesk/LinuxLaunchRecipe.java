package io.github.mekhontsev.magicdesk;

import java.util.List;
import java.util.TreeSet;

/** Linux entry adapters produce ordinary Exec recipes, never own containers or privilege startup. */
final class LinuxLaunchRecipe {
    enum Presentation { TERMINAL, APPLICATION, DESKTOP }
    enum Kind { PROOT, SCRIPT }

    record Environment(Kind kind, String target, DesktopExecBackend backend, String keyboardDirectory) {
        Environment(Kind kind, String target) { this(kind, target, DesktopExecBackend.TERMUX, ""); }
        Environment {
            if (kind == null) throw new IllegalArgumentException("Select a Linux launch method");
            target = target == null ? "" : target.trim();
            if (kind == Kind.PROOT) requireName(target);
            else if (!target.startsWith("/") || target.indexOf('\0') >= 0 || target.length() > 4096)
                throw new IllegalArgumentException("Enter the absolute path of a launcher script");
            if (backend == null) throw new IllegalArgumentException("Select an executor");
            if (kind == Kind.PROOT && backend != DesktopExecBackend.TERMUX)
                throw new IllegalArgumentException("proot-distro requires Termux");
            keyboardDirectory = DesktopExecWorkingDirectory.normalize(keyboardDirectory);
        }
    }

    static List<String> parseInstalledProot(String output) {
        if (output == null || output.length() > 32768)
            throw new IllegalArgumentException("Invalid proot-distro list result");
        TreeSet<String> names = new TreeSet<>();
        for (String line : output.split("\\r?\\n")) {
            String name = line.trim();
            if (name.isEmpty()) continue;
            requireName(name);
            names.add(name);
            if (names.size() > 128) throw new IllegalArgumentException("Too many PRoot environments");
        }
        return List.copyOf(names);
    }

    static DesktopApplicationShortcut build(String name, Environment environment, String command,
            String directory, String user, Presentation presentation) {
        return build(name, environment, command, directory, user, presentation, GraphicalProtocol.X11);
    }

    static DesktopApplicationShortcut build(String name, Environment environment, String command,
            String directory, String user, Presentation presentation, GraphicalProtocol protocol) {
        if (protocol == null) throw new IllegalArgumentException("Select a graphical protocol");
        user = user == null ? "" : user.trim();
        if (!user.isEmpty() && !user.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}\\$?"))
            throw new IllegalArgumentException("Invalid Linux user name");
        command = DesktopExecCommand.normalize(command);
        directory = DesktopExecWorkingDirectory.normalize(directory);
        if (command.isEmpty() && presentation != Presentation.TERMINAL)
            throw new IllegalArgumentException("Enter a Linux command");

        boolean graphical = presentation != Presentation.TERMINAL;
        StringBuilder host = new StringBuilder("set -eu; ");
        if (graphical) host.append(protocol == GraphicalProtocol.X11
                ? ": \"${DISPLAY:?Missing X11 display}\" \"${XAUTHORITY:?Missing X11 authorization}\"; "
                : ": \"${WAYLAND_DISPLAY:?Missing Wayland display}\" \"${MAGICDESK_WAYLAND_RUNTIME:?Missing Wayland runtime}\"; ");
        final boolean proot = environment.kind() == Kind.PROOT;
        host.append("exec ").append(proot ? "proot-distro login --isolated" : q(environment.target()));
        if (!user.isEmpty()) host.append(" --user ").append(q(user));
        if (!directory.isEmpty()) host.append(" --work-dir ").append(q(directory));
        if (graphical && proot) {
            host.append(protocol == GraphicalProtocol.X11
                    ? " --shared-tmp --bind \"$MAGICDESK_X11_RUNTIME:/tmp/magicdesk-x11\""
                        + " --env \"DISPLAY=$DISPLAY\" --env XAUTHORITY=/tmp/magicdesk-x11/Xauthority"
                    : " --bind \"$MAGICDESK_WAYLAND_RUNTIME:/tmp/magicdesk-wayland\""
                        + " --env \"WAYLAND_DISPLAY=/tmp/magicdesk-wayland/$WAYLAND_DISPLAY\"");
            host.append(" --bind \"$MAGICDESK_GUEST_FILES_HELPER:/tmp/magicdesk-guest-files\""
                    + " --env \"MAGICDESK_GUEST_FILES_SOCKET=$MAGICDESK_GUEST_FILES_SOCKET\""
                    + " --env \"MAGICDESK_GUEST_FILES_TOKEN=$MAGICDESK_GUEST_FILES_TOKEN\"");
        }
        if (proot) host.append(' ').append(q(environment.target()));
        if (!command.isEmpty()) {
            String guest = command;
            if (graphical) {
                // Each graphical launch owns its D-Bus session and private runtime directory.
                guest = "set -eu; umask 077; XDG_RUNTIME_DIR=$(mktemp -d /tmp/magicdesk-runtime.XXXXXX); "
                        + "export XDG_RUNTIME_DIR XDG_SESSION_TYPE=" + protocol.wireName + "; "
                        + "trap 'rm -rf -- \"$XDG_RUNTIME_DIR\"' EXIT; "
                        + "dbus-run-session -- /bin/sh -lc " + q(command);
            }
            host.append(graphical ? " -- /tmp/magicdesk-guest-files -- /bin/sh -lc " : " -- /bin/sh -lc ").append(q(guest));
        }
        if (graphical && environment.backend() == DesktopExecBackend.SHELL && environment.keyboardDirectory().isEmpty())
            throw new IllegalArgumentException("Enter the XKB data directory in the prepared Linux environment");
        String exec = DesktopExecTemplate.encodeArguments(List.of("sh", "-c", host.toString()));
        DesktopExecTemplate.expandArguments(exec, DesktopLaunchArguments.empty(), name, "", "");
        return new DesktopApplicationShortcut(name, graphical ? "computer" : "utilities-terminal",
                exec, null, "", DesktopLaunchMode.AUTO, false, environment.backend(),
                !graphical).withLiteralExec(true).withGraphics(graphical
                        ? new GraphicalLaunchOptions(protocol, presentation == Presentation.DESKTOP, environment.keyboardDirectory(), "",
                                environment.kind().name() + ":" + environment.target().length() + ":"
                                        + environment.target() + ":" + user) : null);
    }

    private static void requireName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))
            throw new IllegalArgumentException("Invalid PRoot environment name");
    }

    private static String q(String value) { return ShellCommandLine.quote(value); }
    private LinuxLaunchRecipe() { }
}
