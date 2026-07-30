package io.github.mekhontsev.magicdesk;

import android.app.Application;
import android.content.Context;

public final class MagicDeskApplication extends Application {
    private static Context sApplicationContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sApplicationContext = getApplicationContext();
        RuntimeAccess.initialize(this);
        ShizukuAccess.initialize(this);
        CompatibilityDiagnostics.initialize(this);
    }

    static Context applicationContext() {
        return sApplicationContext;
    }
}
