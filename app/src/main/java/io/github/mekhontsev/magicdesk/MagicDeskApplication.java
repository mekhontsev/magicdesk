package io.github.mekhontsev.magicdesk;

import android.app.Application;
import android.content.Context;

public final class MagicDeskApplication extends Application {
    private static Context sApplicationContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sApplicationContext = getApplicationContext();
        ShellAccess.initialize();
        CompatibilityDiagnostics.initialize(this);
    }

    static Context applicationContext() {
        return sApplicationContext;
    }
}
