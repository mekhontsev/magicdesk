package io.github.mekhontsev.magicdesk;

/** Prevents an old desktop controller from clearing a replacement session. */
final class DesktopTaskConfigurationGuard {
    private DesktopTaskConfigurationGuard() { }

    static boolean canClear(
            final int expectedDisplayId,
            final int configuredDisplayId,
            final int managedAreaDisplayId) {
        return expectedDisplayId >= 0
                && configuredDisplayId == expectedDisplayId
                && (managedAreaDisplayId < 0
                        || managedAreaDisplayId == expectedDisplayId);
    }
}
