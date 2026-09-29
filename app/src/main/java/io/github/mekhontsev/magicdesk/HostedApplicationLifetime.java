package io.github.mekhontsev.magicdesk;

/** Main-thread launch and window observations; window destruction is not process completion. */
final class HostedApplicationLifetime {
    private boolean hadWindows, windowsPresent, launchFinished;

    void windows(boolean present, boolean mapped) {
        windowsPresent = present;
        hadWindows |= mapped;
    }

    void completeLaunch() { launchFinished = true; }
    boolean hadWindows() { return hadWindows; }
    boolean launchFinished() { return launchFinished; }
    boolean quiescent() { return hadWindows && !windowsPresent && launchFinished; }
}
