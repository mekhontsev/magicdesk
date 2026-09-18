package io.github.mekhontsev.magicdesk;

/** Graphical presentation and keyboard data, independent of command execution identity. */
record X11LaunchOptions(boolean desktop, String keyboardDirectory) {
    X11LaunchOptions {
        keyboardDirectory = DesktopExecWorkingDirectory.normalize(keyboardDirectory);
    }
}
