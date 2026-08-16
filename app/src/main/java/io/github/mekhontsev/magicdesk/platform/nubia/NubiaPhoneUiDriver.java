package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.PlatformPhoneUiDriver;
import io.github.mekhontsev.magicdesk.TaskRepository;

import android.content.Context;

import java.util.List;

/** RedMagic phone UI integration used around external desktop sessions. */
final class NubiaPhoneUiDriver implements PlatformPhoneUiDriver {
    private static final String INPUT_PANEL_ACTIVITY =
            "cn.nubia.keymapcenter.mirror.MirrorInputActivity";
    private static final String[] OBSERVED_SETTINGS = {
            ConsoleModeState.PHONE_SCREEN_OFF_SETTING
    };

    @Override
    public TaskEventGuard createInputPanelGuard(
            final Object taskService,
            final InputOwner inputOwner) {
        return new NubiaMirrorInputPanelGuard(taskService, inputOwner);
    }

    @Override
    public NavigationGuard createNavigationGuard() {
        return new SystemNavigationGuard();
    }

    @Override
    public boolean isInputPanelTask(final TaskRepository.TaskEntry task) {
        if (task == null) {
            return false;
        }
        return hasActivity(task.componentName)
                || hasActivity(task.topActivityName);
    }

    @Override
    public boolean requiresPhoneUiReconciliation() {
        return true;
    }

    @Override
    public boolean shouldRestoreLocalDesktopHost(
            final int displayId,
            final List<TaskRepository.TaskEntry> tasks,
            final String desktopPackage) {
        return LocalDesktopHostRecoveryPolicy.shouldRestore(
                displayId, tasks, desktopPackage);
    }

    @Override
    public boolean usesMirrorInputPanel() {
        return true;
    }

    @Override
    public boolean isPhoneScreenOff(final Context context) {
        return ConsoleModeState.isPhoneScreenOff(context);
    }

    @Override
    public boolean isPhoneScreenControlActive() {
        return PhoneDisplayGuard.isActive();
    }

    @Override
    public boolean setPhoneScreenOff(final boolean screenOff) {
        return screenOff
                ? PhoneDisplayGuard.enable() : PhoneDisplayGuard.disable();
    }

    @Override
    public void requestPhoneScreenRestore() {
        PhoneDisplayGuard.requestRestore();
    }

    @Override
    public String[] observedSettingKeys() {
        return OBSERVED_SETTINGS.clone();
    }

    @Override
    public void hideExternalAssistPanel() {
        NubiaHostAssistPanelController.hideIfPresent();
    }

    private static boolean hasActivity(final String componentName) {
        return componentName != null
                && componentName.endsWith(INPUT_PANEL_ACTIVITY);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
