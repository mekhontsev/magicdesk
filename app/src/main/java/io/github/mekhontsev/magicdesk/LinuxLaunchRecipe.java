package io.github.mekhontsev.magicdesk;

import java.util.List;
import java.util.TreeSet;

/** Linux entry adapters produce ordinary Exec recipes, never own containers or privilege startup. */
final class LinuxLaunchRecipe {
    enum Presentation { TERMINAL, APPLICATION, DESKTOP }
    enum Kind { PROOT, SCRIPT, GUEST, MANAGED_GUEST }

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
            if ((kind == Kind.GUEST || kind == Kind.MANAGED_GUEST) && backend != DesktopExecBackend.SHELL)
                throw new IllegalArgumentException("Guest runtime requires the Shell executor");
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
        if (environment.kind() != Kind.GUEST && environment.kind() != Kind.MANAGED_GUEST && !user.isEmpty() && !user.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}\\$?"))
            throw new IllegalArgumentException("Invalid Linux user name");
        command = DesktopExecCommand.normalize(command);
        directory = DesktopExecWorkingDirectory.normalize(directory);
        if (command.isEmpty() && presentation != Presentation.TERMINAL)
            throw new IllegalArgumentException("Enter a Linux command");
        if (environment.kind() == Kind.GUEST || environment.kind() == Kind.MANAGED_GUEST)
            return guest(name, environment, command, directory, user, presentation, protocol);

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
            if (presentation == Presentation.APPLICATION) host.append(
                    " --bind \"$MAGICDESK_APPEARANCE_HELPER:/tmp/magicdesk-linux-settings\""
                    + " --env \"MAGICDESK_APPEARANCE_SOCKET=$MAGICDESK_APPEARANCE_SOCKET\""
                    + " --env \"MAGICDESK_APPEARANCE_TOKEN=$MAGICDESK_APPEARANCE_TOKEN\"");
        }
        if (proot) host.append(' ').append(q(environment.target()));
        if (!command.isEmpty()) {
            String guest = command;
            if (graphical) {
                // Each graphical launch owns its D-Bus session and private runtime directory.
                guest = LinuxGraphicalEnvironment.wrap(protocol,
                        (presentation == Presentation.APPLICATION ? "/tmp/magicdesk-linux-settings -- " : "")
                        + "/bin/sh -lc " + q(command));
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

    private static DesktopApplicationShortcut guest(String name, Environment environment, String command,
            String directory, String user, Presentation presentation, GraphicalProtocol protocol) {
        boolean graphical = presentation != Presentation.TERMINAL;
        boolean managed = environment.kind() == Kind.MANAGED_GUEST;
        String keyboard = environment.keyboardDirectory().isEmpty() ? "guest:" + environment.target() : environment.keyboardDirectory();
        var identity = new GuestEnvironment(environment.target(), managed ? "" : "/tmp", user, managed);
        String cwd = directory.isEmpty() && !managed ? "/" : directory;
        var plan = graphical ? GuestGraphicalConnection.plan(identity, cwd, command, protocol)
                : new GuestLaunchPlan(identity, cwd, command.isEmpty()
                        ? managed ? List.of() : List.of("/bin/sh", "-l") : List.of("/bin/sh", "-lc", command));
        String exec = DesktopExecTemplate.encodeArguments(graphical
                ? List.of("sh", "-c", GuestGraphicalConnection.invocation(plan, protocol, true)) : plan.arguments());
        DesktopExecTemplate.expandArguments(exec, DesktopLaunchArguments.empty(), name, "", "");
        return new DesktopApplicationShortcut(name, graphical ? "computer" : "utilities-terminal",
                exec, null, "", DesktopLaunchMode.AUTO, false, DesktopExecBackend.SHELL, !graphical)
                .withLiteralExec(true).withGraphics(graphical ? new GraphicalLaunchOptions(protocol,
                        presentation == Presentation.DESKTOP, keyboard, "",
                        "GUEST:" + environment.target().length() + ":" + environment.target() + ":" + user, GraphicalConnectionMode.ROUTED) : null);
    }

    private static String q(String value) { return ShellCommandLine.quote(value); }
    private LinuxLaunchRecipe() { }
}
