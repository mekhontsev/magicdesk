package io.github.mekhontsev.magicdesk;

/** Explicitly shared terminal content, never a guessed foreground-process root. */
final class TerminalFilesEnvironment {
    static final String DIRECTORY = "MAGICDESK_TERMINAL_FILES_DIR";
    static final String GUEST_DIRECTORY = "/tmp/magicdesk-terminal-files";

    static String quoteRelativePath(String relative) {
        return "\"${" + DIRECTORY + ":?Session files unavailable}\"/" + ShellCommandLine.quote(relative);
    }

    static String shrootArguments() {
        return "if [ -n \"${" + DIRECTORY + "-}\" ]; then\n"
                + "  set -- --bind-create \"$" + DIRECTORY + "\" " + GUEST_DIRECTORY + " "
                + "--env " + DIRECTORY + "=" + GUEST_DIRECTORY + " \"$@\"\nfi\n";
    }

    static String prootArguments() {
        return "if [ -n \"${" + DIRECTORY + "-}\" ]; then\n"
                + "  set -- --bind \"$" + DIRECTORY + ":" + GUEST_DIRECTORY + "\" "
                + "--env " + DIRECTORY + "=" + GUEST_DIRECTORY + " \"$@\"\nfi\n";
    }

    private TerminalFilesEnvironment() { }
}
