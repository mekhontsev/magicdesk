package io.github.mekhontsev.magicdesk;

/** Graphical presentation and keyboard data, independent of command execution identity. */
record X11LaunchOptions(boolean desktop, String keyboardDirectory, String startupClass) {
    X11LaunchOptions(boolean desktop, String keyboardDirectory) { this(desktop, keyboardDirectory, ""); }
    X11LaunchOptions {
        keyboardDirectory = DesktopExecWorkingDirectory.normalize(keyboardDirectory);
        startupClass = startupClass == null ? "" : startupClass;
        if (startupClass.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid StartupWMClass");
    }
}
