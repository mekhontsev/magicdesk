package io.github.mekhontsev.magicdesk;

/** Stable capability description for an Exec backend. */
final class DesktopExecCapabilities {
    final boolean background;
    final boolean terminal;
    final boolean workingDirectory;
    final boolean completionResult;

    DesktopExecCapabilities(
            final boolean background,
            final boolean terminal,
            final boolean workingDirectory,
            final boolean completionResult) {
        this.background = background;
        this.terminal = terminal;
        this.workingDirectory = workingDirectory;
        this.completionResult = completionResult;
    }

    String report() {
        return "background=" + background
                + ",terminal=" + terminal
                + ",cwd=" + workingDirectory
                + ",completion=" + completionResult;
    }
}
