package io.github.mekhontsev.magicdesk;

import java.io.IOException;

final class PrivilegedCommandRunner {
    private PrivilegedCommandRunner() {
    }

    static String run(final String command) throws IOException {
        if (RuntimeAccess.allowsShizukuCommands()) {
            return ShizukuAccess.run(command);
        }
        throw new IOException(
                "Privileged command is unavailable: backend="
                        + RuntimeAccess.backendName());
    }

}
