package io.github.mekhontsev.magicdesk;

/** Immutable command portion of a desktop launch request. */
final class DesktopExecSpec {
    final DesktopExecBackend backend;
    final String command;
    final boolean terminal;
    final String workingDirectory;
    final X11LaunchOptions x11;
    final boolean literal;

    DesktopExecSpec(
            final DesktopExecBackend backend,
            final String command,
            final boolean terminal) {
        this(backend, command, terminal, "");
    }

    DesktopExecSpec(
            final DesktopExecBackend backend,
            final String command,
            final boolean terminal,
            final String workingDirectory) {
        this(backend, command, terminal, workingDirectory, null, false);
    }

    DesktopExecSpec(DesktopExecBackend backend, String command, boolean terminal,
            String workingDirectory, X11LaunchOptions x11, boolean literal) {
        this.backend = backend == null
                ? DesktopExecBackend.SHELL : backend;
        this.command = DesktopExecCommand.normalize(command);
        if (this.command.isEmpty()) {
            throw new IllegalArgumentException("missing desktop Exec command");
        }
        this.terminal = terminal;
        this.x11 = x11;
        this.literal = literal;
        this.workingDirectory = DesktopExecWorkingDirectory.normalize(
                workingDirectory);
    }

    DesktopExecSpec withCommand(final String value) {
        return new DesktopExecSpec(
                backend, value, terminal, workingDirectory, x11, literal);
    }
}
