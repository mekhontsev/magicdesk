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
        user = user == null ? "" : user.trim();
        if (!user.isEmpty() && !user.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}\\$?"))
            throw new IllegalArgumentException("Invalid Linux user name");
        command = DesktopExecCommand.normalize(command);
        directory = DesktopExecWorkingDirectory.normalize(directory);
        if (command.isEmpty() && presentation != Presentation.TERMINAL)
            throw new IllegalArgumentException("Enter a Linux command");

        boolean graphical = presentation != Presentation.TERMINAL;
        StringBuilder host = new StringBuilder("set -eu; ");
        if (graphical) host.append(": \"${DISPLAY:?Missing X11 display}\" \"${XAUTHORITY:?Missing X11 authorization}\"; ");
        final boolean proot = environment.kind() == Kind.PROOT;
        host.append("exec ").append(proot ? "proot-distro login --isolated" : q(environment.target()));
        if (!user.isEmpty()) host.append(" --user ").append(q(user));
        if (!directory.isEmpty()) host.append(" --work-dir ").append(q(directory));
        if (graphical && proot) host.append(" --shared-tmp --bind \"$XAUTHORITY:/tmp/magicdesk.Xauthority\""
                + " --env \"DISPLAY=$DISPLAY\" --env XAUTHORITY=/tmp/magicdesk.Xauthority");
        if (proot) host.append(' ').append(q(environment.target()));
        if (!command.isEmpty()) {
            String guest = command;
            if (graphical) {
                // Each graphical launch owns its D-Bus session and private runtime directory.
                guest = "set -eu; umask 077; XDG_RUNTIME_DIR=$(mktemp -d /tmp/magicdesk-runtime.XXXXXX); "
                        + "export XDG_RUNTIME_DIR XDG_SESSION_TYPE=x11; "
                        + "trap 'rm -rf -- \"$XDG_RUNTIME_DIR\"' EXIT; "
                        + "dbus-run-session -- /bin/sh -lc " + q(command);
            }
            host.append(" -- /bin/sh -lc ").append(q(guest));
        }
        if (graphical && environment.backend() == DesktopExecBackend.SHELL && environment.keyboardDirectory().isEmpty())
            throw new IllegalArgumentException("Enter the XKB data directory in the prepared Linux environment");
        String exec = DesktopExecTemplate.encodeArguments(List.of("sh", "-c", host.toString()));
        DesktopExecTemplate.expandArguments(exec, DesktopLaunchArguments.empty(), name, "", "");
        return new DesktopApplicationShortcut(name, graphical ? "computer" : "utilities-terminal",
                exec, null, "", DesktopLaunchMode.AUTO, false, environment.backend(),
                !graphical).withLiteralExec(true).withX11(graphical
                        ? new X11LaunchOptions(presentation == Presentation.DESKTOP, environment.keyboardDirectory()) : null);
    }

    private static void requireName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))
            throw new IllegalArgumentException("Invalid PRoot environment name");
    }

    private static String q(String value) { return ShellCommandLine.quote(value); }
    private LinuxLaunchRecipe() { }
}
