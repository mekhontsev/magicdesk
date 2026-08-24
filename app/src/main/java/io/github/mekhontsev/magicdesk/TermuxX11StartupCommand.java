package io.github.mekhontsev.magicdesk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure command handling for the optional Termux:X11 session. */
final class TermuxX11StartupCommand {
    static final String DEFAULT = "termux-x11 :1";
    static final int MAX_LENGTH = DesktopExecCommand.MAX_LENGTH;
    private static final Pattern DISPLAY_ARGUMENT = Pattern.compile(
            "^(?:[^\\s;&|]*/)?termux-x11\\s+"
                    + "(:[0-9]+)(?=\\s|$)");

    private TermuxX11StartupCommand() {
    }

    static String normalize(final String command) {
        if (command == null) {
            return DEFAULT;
        }
        final String normalized = DesktopExecCommand.normalize(command);
        if (normalized.isEmpty()) {
            return DEFAULT;
        }
        return normalized;
    }

    static String startOrReconnect(final String command) {
        final String normalized = normalize(command);
        final String display = requestedDisplay(normalized);
        if (display.isEmpty()) {
            return normalized;
        }
        // The official launcher changes argv[0] with app_process --nice-name,
        // while Android still exposes its comm name as "main". Inspect argv[0]
        // directly so the guard works without an optional procps package.
        return "requested=" + ShellCommandLine.quote(display) + "\n"
                + "for cmdline in /proc/[0-9]*/cmdline; do\n"
                + "  argv0=\n"
                + "  if IFS= read -r -d '' argv0 < \"$cmdline\""
                + " 2>/dev/null; then\n"
                + "    case \"$argv0\" in\n"
                + "      \"termux-x11 com.termux.x11 $requested\"|"
                + "\"termux-x11 com.termux.x11 $requested \"*)\n"
                // Termux:X11's viewer uses this loopback handshake to ask an
                // existing server to broadcast a fresh Binder connection.
                + "        if exec 3<>/dev/tcp/127.0.0.1/7892; then\n"
                + "          printf '0xDEADBEEF\\0' >&3\n"
                + "          exec 3>&-\n"
                + "          exit 0\n"
                + "        fi\n"
                // If the listener disappeared while the process was visible,
                // run the configured startup command instead of reporting a
                // false successful reconnect.
                + "        break\n"
                + "        ;;\n"
                + "    esac\n"
                + "  fi\n"
                + "done\n"
                + normalized;
    }

    static String reconnect(final String command) {
        final String display = requestedDisplay(normalize(command));
        if (display.isEmpty()) {
            throw new IllegalArgumentException(
                    "Termux:X11 command has no direct :N display argument");
        }
        return "requested=" + ShellCommandLine.quote(display) + "\n"
                + processScan(
                        "if exec 3<>/dev/tcp/127.0.0.1/7892; then\n"
                        + "  printf '0xDEADBEEF\\0' >&3\n"
                        + "  exec 3>&-\n"
                        + "  printf 'reconnectedDisplay=%s\\n' \"$requested\"\n"
                        + "  exit 0\n"
                        + "fi\n"
                        + "printf 'listener unavailable for %s\\n'"
                        + " \"$requested\" >&2\n"
                        + "exit 69")
                + "printf 'server not found for %s\\n' \"$requested\" >&2\n"
                + "exit 66";
    }

    static String statusProbe(final String command) {
        final String display = requestedDisplay(normalize(command));
        final String requested = display.isEmpty() ? "unknown" : display;
        return "requested=" + ShellCommandLine.quote(requested) + "\n"
                + "server_found=false\n"
                + "server_pid=\n"
                + "server_display=\n"
                + processScan(
                        "server_found=true\n"
                        + "server_pid=${cmdline#/proc/}\n"
                        + "server_pid=${server_pid%/cmdline}\n"
                        + "server_display=$requested\n"
                        + "break")
                + "socket_listening=false\n"
                // Prefer the matching process's network namespace, then fall
                // back to the global socket tables exposed to shell.
                + "for table in /proc/$server_pid/net/tcp"
                + " /proc/$server_pid/net/tcp6"
                + " /proc/net/tcp /proc/net/tcp6; do\n"
                + "  [ -r \"$table\" ] || continue\n"
                + "  while read -r slot local_address remote_address state rest; do\n"
                + "    case \"$local_address:$state\" in\n"
                + "      *:1ED4:0A) socket_listening=true; break ;;\n"
                + "    esac\n"
                + "  done < \"$table\"\n"
                + "  [ \"$socket_listening\" = true ] && break\n"
                + "done\n"
                + "printf 'format=1\\nrequestedDisplay=%s\\nserverFound=%s\\n"
                + "serverPid=%s\\nserverDisplay=%s\\nsocketListening=%s\\n'"
                + " \"$requested\" \"$server_found\" \"$server_pid\""
                + " \"$server_display\" \"$socket_listening\"";
    }

    static String requestedDisplay(final String command) {
        final Matcher matcher = DISPLAY_ARGUMENT.matcher(
                command == null ? "" : command);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String processScan(final String matchBody) {
        return "for cmdline in /proc/[0-9]*/cmdline; do\n"
                + "  argv0=\n"
                + "  if IFS= read -r -d '' argv0 < \"$cmdline\""
                + " 2>/dev/null; then\n"
                + "    case \"$argv0\" in\n"
                + "      \"termux-x11 com.termux.x11 $requested\"|"
                + "\"termux-x11 com.termux.x11 $requested \"*)\n"
                + indent(matchBody, 8)
                + "        ;;\n"
                + "    esac\n"
                + "  fi\n"
                + "done\n";
    }

    private static String indent(final String value, final int spaces) {
        final String prefix = " ".repeat(spaces);
        return prefix + value.replace("\n", "\n" + prefix) + "\n";
    }
}
