package io.github.mekhontsev.magicdesk;

/** Enter after display variables are installed. Guest adapters do this inside their guest D-Bus session. */
final class LinuxAutomationEnvironment {
    static String command(String command, String shell) {
        return "if command -v dbus-run-session >/dev/null 2>&1 && [ -n \"${MAGICDESK_AUTOMATION_HELPER:-}\" ]; then\n"
                + "exec \"$MAGICDESK_AUTOMATION_HELPER\" --session "
                + ShellCommandLine.quote(shell) + " -c " + ShellCommandLine.quote(command)
                + "\nelse\n" + command + "\nfi\n";
    }
    private LinuxAutomationEnvironment() { }
}
