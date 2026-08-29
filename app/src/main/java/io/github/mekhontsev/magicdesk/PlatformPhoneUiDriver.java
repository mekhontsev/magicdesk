package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/** Firmware-specific phone UI behavior while a desktop session is active. */
public interface PlatformPhoneUiDriver {
    interface NavigationGuard extends AutoCloseable {
        enum Scope {
            LOCAL_DESKTOP,
            CRASHED_LAUNCHER
        }

        void acquire(IBinder ownerToken, Scope scope);

        void release(IBinder ownerToken);

        @Override
        void close();
    }

    boolean isAvailable();

    NavigationGuard createNavigationGuard();

    boolean requiresPhoneUiReconciliation();

    boolean protectsPhoneLauncherAfterCrash();

    boolean isTransientSecondaryHomeIntent(Intent intent);

    boolean requiresPhoneImeRouting();

    boolean isPhoneScreenOff(Context context);

    boolean isPhoneScreenControlActive();

    boolean setPhoneScreenOff(boolean screenOff, int desktopDisplayId);

    void requestPhoneScreenRestore();

    String[] observedSettingKeys();
}
