package io.github.mekhontsev.magicdesk;

import android.app.Application;
import android.content.Context;

public final class MagicDeskApplication extends Application {
    private static Context sApplicationContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sApplicationContext = getApplicationContext();
        AndroidActivityResultStore.releaseOrphanedPersistedUris(this);
        DesktopHomeStartupGuard.relinquishStaleHome(this);
        IntegrationPackage.active();
        ShellBackend.active();
        ShellPrivilegePolicy.forceShell();
        ShellAccess.initialize();
        CompatibilityDiagnostics.initialize(this);
        DesktopAutomationEventJournal.record(
                "process",
                "started",
                true,
                "pid=" + android.os.Process.myPid());
    }

    public static Context applicationContext() {
        return sApplicationContext;
    }
}
