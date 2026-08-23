package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.PlatformPhoneUiDriver;
import io.github.mekhontsev.magicdesk.TaskRepository;

import android.content.Context;
import android.content.Intent;

/** RedMagic phone UI integration used around external desktop sessions. */
final class NubiaPhoneUiDriver implements PlatformPhoneUiDriver {
    private static final String INPUT_PANEL_ACTIVITY =
            "cn.nubia.keymapcenter.mirror.MirrorInputActivity";
    private static final String SECONDARY_HOME_ACTIVITY =
            "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher";
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
    public boolean protectsPhoneLauncherAfterCrash() {
        return true;
    }

    @Override
    public boolean isTransientSecondaryHomeIntent(final Intent intent) {
        return intent != null && isTransientSecondaryHome(
                intent.hasCategory(Intent.CATEGORY_SECONDARY_HOME),
                intent.getComponent() == null
                        ? null : intent.getComponent().getClassName());
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
    public boolean setPhoneScreenOff(
            final boolean screenOff,
            final int desktopDisplayId) {
        return screenOff
                ? PhoneDisplayGuard.enable(desktopDisplayId)
                : PhoneDisplayGuard.disable();
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

    static boolean isTransientSecondaryHome(
            final boolean hasSecondaryHomeCategory,
            final String activityName) {
        return hasSecondaryHomeCategory
                && SECONDARY_HOME_ACTIVITY.equals(activityName);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
