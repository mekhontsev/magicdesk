package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/** Firmware-specific phone UI behavior while a desktop session is active. */
public interface PlatformPhoneUiDriver {
    interface InputOwner {
        boolean isActive();

        void preservePointer();

        void reclaimInput();
    }

    interface TaskEventGuard extends AutoCloseable {
        void configure(int displayId);

        void onTaskAppeared(int taskId, ComponentName componentName);

        void onTaskRemoved(int taskId);

        @Override
        void close();
    }

    interface NavigationGuard extends AutoCloseable {
        void acquire(IBinder ownerToken);

        void release(IBinder ownerToken);

        @Override
        void close();
    }

    boolean isAvailable();

    TaskEventGuard createInputPanelGuard(
            Object taskService,
            InputOwner inputOwner);

    NavigationGuard createNavigationGuard();

    boolean isInputPanelTask(TaskRepository.TaskEntry task);

    boolean requiresPhoneUiReconciliation();

    boolean protectsPhoneLauncherAfterCrash();

    boolean isTransientSecondaryHomeIntent(Intent intent);

    boolean usesMirrorInputPanel();

    boolean isPhoneScreenOff(Context context);

    boolean isPhoneScreenControlActive();

    boolean setPhoneScreenOff(boolean screenOff, int desktopDisplayId);

    void requestPhoneScreenRestore();

    String[] observedSettingKeys();

    void hideExternalAssistPanel();
}
