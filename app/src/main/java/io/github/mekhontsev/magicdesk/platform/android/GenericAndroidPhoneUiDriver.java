package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.PlatformPhoneUiDriver;
import io.github.mekhontsev.magicdesk.TaskRepository;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/** No-op phone UI integration for the Generic Android profile. */
final class GenericAndroidPhoneUiDriver implements PlatformPhoneUiDriver {
    private static final String[] NO_SETTINGS = new String[0];

    @Override
    public TaskEventGuard createInputPanelGuard(
            final Object taskService,
            final InputOwner inputOwner) {
        return new TaskEventGuard() {
            @Override
            public void configure(final int displayId) {
            }

            @Override
            public void onTaskAppeared(
                    final int taskId,
                    final ComponentName componentName) {
            }

            @Override
            public void onTaskRemoved(final int taskId) {
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public NavigationGuard createNavigationGuard() {
        return new NavigationGuard() {
            @Override
            public void acquire(final IBinder ownerToken) {
            }

            @Override
            public void release(final IBinder ownerToken) {
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public boolean isInputPanelTask(final TaskRepository.TaskEntry task) {
        return false;
    }

    @Override
    public boolean requiresPhoneUiReconciliation() {
        return false;
    }

    @Override
    public boolean protectsPhoneLauncherAfterCrash() {
        return false;
    }

    @Override
    public boolean isTransientSecondaryHomeIntent(final Intent intent) {
        return false;
    }

    @Override
    public boolean usesMirrorInputPanel() {
        return false;
    }

    @Override
    public boolean isPhoneScreenOff(final Context context) {
        return false;
    }

    @Override
    public boolean isPhoneScreenControlActive() {
        return false;
    }

    @Override
    public boolean setPhoneScreenOff(
            final boolean screenOff,
            final int desktopDisplayId) {
        return false;
    }

    @Override
    public void requestPhoneScreenRestore() {
    }

    @Override
    public String[] observedSettingKeys() {
        return NO_SETTINGS;
    }

    @Override
    public void hideExternalAssistPanel() {
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
