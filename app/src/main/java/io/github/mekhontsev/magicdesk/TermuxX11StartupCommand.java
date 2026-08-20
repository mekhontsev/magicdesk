package io.github.mekhontsev.magicdesk;

/** Pure command handling for the optional Termux:X11 session. */
final class TermuxX11StartupCommand {
    static final String DEFAULT = "termux-x11 :1";
    static final int MAX_LENGTH = 4096;

    private TermuxX11StartupCommand() {
    }

    static String normalize(final String command) {
        if (command == null) {
            return DEFAULT;
        }
        final String normalized = command.trim();
        if (normalized.isEmpty()) {
            return DEFAULT;
        }
        if (normalized.length() > MAX_LENGTH
                || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid Termux:X11 command");
        }
        return normalized;
    }

    static String startOrReconnect(final String command) {
        // The official launcher changes argv[0] with app_process --nice-name,
        // while Android still exposes its comm name as "main". Inspect argv[0]
        // directly so the guard works without an optional procps package.
        return "for cmdline in /proc/[0-9]*/cmdline; do\n"
                + "  argv0=\n"
                + "  if IFS= read -r -d '' argv0 < \"$cmdline\""
                + " 2>/dev/null; then\n"
                + "    case \"$argv0\" in\n"
                + "      \"termux-x11 com.termux.x11 \"*)\n"
                // Termux:X11's viewer uses this loopback handshake to ask an
                // existing server to broadcast a fresh Binder connection.
                + "        if exec 3<>/dev/tcp/127.0.0.1/7892; then\n"
                + "          printf '0xDEADBEEF\\0' >&3\n"
                + "          exec 3>&-\n"
                + "        fi\n"
                + "        exit 0\n"
                + "        ;;\n"
                + "    esac\n"
                + "  fi\n"
                + "done\n"
                + normalize(command);
    }
}
