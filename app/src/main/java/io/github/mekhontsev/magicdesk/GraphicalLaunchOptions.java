package io.github.mekhontsev.magicdesk;

/** Graphical presentation and keyboard data, independent of command execution identity. */
record GraphicalLaunchOptions(GraphicalProtocol protocol, boolean desktop, String keyboardDirectory,
        String startupClass, String fileEnvironment) {
    GraphicalLaunchOptions(boolean desktop, String keyboardDirectory, String startupClass, String fileEnvironment) {
        this(GraphicalProtocol.X11, desktop, keyboardDirectory, startupClass, fileEnvironment);
    }
    GraphicalLaunchOptions(boolean desktop, String keyboardDirectory) { this(desktop, keyboardDirectory, "", ""); }
    GraphicalLaunchOptions(boolean desktop, String keyboardDirectory, String startupClass) {
        this(desktop, keyboardDirectory, startupClass, "");
    }
    GraphicalLaunchOptions {
        if (protocol == null) throw new IllegalArgumentException("Missing graphical protocol");
        keyboardDirectory = DesktopExecWorkingDirectory.normalize(keyboardDirectory);
        startupClass = startupClass == null ? "" : startupClass;
        if (startupClass.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid StartupWMClass");
        fileEnvironment = fileEnvironment == null ? "" : fileEnvironment;
        if (fileEnvironment.indexOf('\0') >= 0 || fileEnvironment.length() > 8192)
            throw new IllegalArgumentException("Invalid Linux file environment");
    }

    void requireSupported() {
        if (protocol == GraphicalProtocol.WAYLAND && (desktop || !fileEnvironment.isEmpty()))
            throw new IllegalArgumentException("Wayland desktop and guest file integration are not available yet");
    }
}
