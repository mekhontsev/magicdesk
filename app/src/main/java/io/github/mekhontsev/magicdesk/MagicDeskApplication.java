package io.github.mekhontsev.magicdesk;

import android.app.Application;

public final class MagicDeskApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CompatibilityDiagnostics.initialize(this);
    }
}
