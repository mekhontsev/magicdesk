package io.github.mekhontsev.magicdesk;

/** Guest-side session policy; the distribution supplies D-Bus and its authentication policy. */
final class LinuxGraphicalEnvironment {
    static String wrap(GraphicalProtocol protocol, String command) {
        String setup = "set -eu; umask 077; XDG_RUNTIME_DIR=$(mktemp -d /tmp/magicdesk-runtime.XXXXXX); "
                + "export XDG_RUNTIME_DIR XDG_SESSION_TYPE=" + protocol.wireName + "; "
                + "trap 'rm -rf -- \"$XDG_RUNTIME_DIR\"' EXIT; ";
        return setup + "dbus-run-session -- " + command;
    }

    private LinuxGraphicalEnvironment() { }
}
