package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.PlatformPhoneUiDriver;
import android.content.Context;
import android.content.Intent;

/** RedMagic phone UI integration used around external desktop sessions. */
final class NubiaPhoneUiDriver implements PlatformPhoneUiDriver {
    private static final String SECONDARY_HOME_ACTIVITY =
            "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher";
    private static final String[] OBSERVED_SETTINGS = {
            NubiaPhoneScreenState.SETTING
    };

    @Override
    public NavigationGuard createNavigationGuard() {
        return new SystemNavigationGuard();
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
    public boolean requiresPhoneImeRouting() {
        return true;
    }

    @Override
    public boolean isPhoneScreenOff(final Context context) {
        return NubiaPhoneScreenState.isOff();
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
