package io.github.mekhontsev.magicdesk;

/** Restores journaled firmware state when access returns, without initializing Desktop. */
final class PlatformDesktopRecovery {
    private PlatformDesktopRecovery() { }

    static void initialize() {
        ShellAccess.addStateListener(snapshot -> {
            if (snapshot.isReady()) {
                TaskCommandQueue.execute(() -> PlatformDrivers.current().projection()
                        .recoverInterruptedDesktopState());
            }
        });
    }
}
