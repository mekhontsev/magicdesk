package io.github.mekhontsev.magicdesk;

import android.app.Application;
import android.content.Context;

public final class MagicDeskApplication extends Application {
    private static Context sApplicationContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sApplicationContext = getApplicationContext();
        DesktopHomeStartupGuard.relinquishStaleHome(this);
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
