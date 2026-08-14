package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;

import java.util.List;

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

    boolean requiresPhoneFreeformCleanup();

    boolean requiresPhoneUiReconciliation();

    boolean shouldRestoreLocalDesktopHost(
            int displayId,
            List<TaskRepository.TaskEntry> tasks,
            String desktopPackage);

    boolean usesMirrorInputPanel();

    boolean isPhoneScreenOff(Context context);

    boolean isPhoneScreenControlActive();

    boolean setPhoneScreenOff(boolean screenOff);

    void requestPhoneScreenRestore();

    String[] observedSettingKeys();

    void hideExternalAssistPanel();
}
