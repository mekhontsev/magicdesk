package io.github.mekhontsev.magicdesk;

/** Validated form data for a terminal-backed Application desktop entry. */
final class DesktopCommandApplicationDraft {
    enum FileArguments {
        NONE(""),
        SINGLE("%f"),
        MULTIPLE("%F");

        final String fieldCode;

        FileArguments(final String fieldCode) {
            this.fieldCode = fieldCode;
        }
    }

    final String name;
    final String command;
    final DesktopExecBackend backend;
    final String workingDirectory;
    final FileArguments fileArguments;
    final String mimeTypes;

    DesktopCommandApplicationDraft(
            final String name,
            final String command,
            final DesktopExecBackend backend,
            final String workingDirectory,
            final FileArguments fileArguments,
            final String mimeTypes) {
        this.name = name == null ? "" : name.trim();
        this.command = command == null ? "" : command.trim();
        this.backend = backend == null ? DesktopExecBackend.SHELL : backend;
        this.workingDirectory = workingDirectory == null
                ? "" : workingDirectory.trim();
        this.fileArguments = fileArguments == null
                ? FileArguments.NONE : fileArguments;
        this.mimeTypes = mimeTypes == null ? "" : mimeTypes.trim();
    }

    DesktopApplicationShortcut build() {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("missing application name");
        }
        String exec = DesktopExecCommand.normalize(command);
        if (fileArguments != FileArguments.NONE
                && !DesktopExecTemplate.acceptsArguments(exec)) {
            exec += " " + fileArguments.fieldCode;
        }
        final DesktopMimeTypes acceptedTypes =
                DesktopMimeTypes.parse(mimeTypes);
        if (!acceptedTypes.isEmpty()
                && !DesktopExecTemplate.acceptsArguments(exec)) {
            throw new IllegalArgumentException(
                    "file types require a file argument");
        }
        return new DesktopApplicationShortcut(
                name,
                "utilities-terminal",
                exec,
                null,
                "",
                DesktopLaunchMode.AUTO,
                false,
                backend,
                true,
                workingDirectory,
                acceptedTypes);
    }

    static String displayName(final String fileName) {
        if (fileName == null) {
            return "";
        }
        final int extension = fileName.lastIndexOf('.');
        return extension > 0 ? fileName.substring(0, extension) : fileName;
    }
}
