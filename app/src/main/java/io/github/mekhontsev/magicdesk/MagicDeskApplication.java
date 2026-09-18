package io.github.mekhontsev.magicdesk;

import android.app.Application;
import android.content.Context;
import android.os.Process;

public final class MagicDeskApplication extends Application {
    private static Context sApplicationContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sApplicationContext = getApplicationContext();
        // Auxiliary Activity processes own only their UI. Runtime startup and
        // recovery belong to the process hosting our service and Binder provider.
        if (!isPrimaryProcess(getProcessName(), getApplicationInfo().processName)) {
            return;
        }
        AndroidActivityResultStore.releaseOrphanedPersistedUris(this);
        DesktopHomeStartupGuard.relinquishStaleHome(this);
        IntegrationPackage.active();
        ShellBackend.active();
        RuntimeLimits.active();
        TermuxConnectionStatus.initialize(this);
        ShellAccess.initialize();
        DesktopSetupStatus.initialize(this);
        CompatibilityDiagnostics.initialize(this);
        DesktopAutomationEventJournal.record(
                "process",
                "started",
                true,
                "pid=" + Process.myPid());
    }

    static boolean isPrimaryProcess(
            final String processName, final String applicationProcessName) {
        return processName != null && processName.equals(applicationProcessName);
    }

    public static Context applicationContext() {
        return sApplicationContext;
    }
}
