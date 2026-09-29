package io.github.mekhontsev.magicdesk;

/** Guest-side session policy; the distribution supplies D-Bus and its authentication policy. */
final class LinuxGraphicalEnvironment {
    enum BusTransport { STANDARD, ABSTRACT }

    static String wrap(GraphicalProtocol protocol, BusTransport transport, String command) {
        String setup = "set -eu; umask 077; XDG_RUNTIME_DIR=$(mktemp -d /tmp/magicdesk-runtime.XXXXXX); "
                + "export XDG_RUNTIME_DIR XDG_SESSION_TYPE=" + protocol.wireName + "; "
                + "trap 'rm -rf -- \"$XDG_RUNTIME_DIR\"' EXIT; ";
        if (transport == BusTransport.STANDARD) return setup + "dbus-run-session -- " + command;
        // dbus-run-session owns readiness and shutdown. Override only the listen address,
        // not the distribution's session.conf, credentials, activation or resource limits.
        String daemon = "#!/bin/sh\nset -eu\naddress=$(dbus-uuidgen)\n"
                + "exec dbus-daemon --address=\"unix:abstract=magicdesk-$address\" \"$@\"\n";
        return setup + "printf '%s' " + ShellCommandLine.quote(daemon)
                + " > \"$XDG_RUNTIME_DIR/dbus-daemon\"; chmod 700 \"$XDG_RUNTIME_DIR/dbus-daemon\"; "
                + "dbus-run-session --dbus-daemon=\"$XDG_RUNTIME_DIR/dbus-daemon\" -- " + command;
    }

    private LinuxGraphicalEnvironment() { }
}
