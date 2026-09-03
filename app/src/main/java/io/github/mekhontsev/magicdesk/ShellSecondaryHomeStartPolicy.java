package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.util.Log;
import android.view.Display;

import java.util.Set;

/** Rejects competing secondary-Home starts while MagicDesk owns the host. */
final class ShellSecondaryHomeStartPolicy implements
        ShellActivityStartController.Listener {
    private static final String TAG = "MagicDeskTasks";
    private static final String MAGICDESK_PACKAGE = BuildConfig.APPLICATION_ID;
    private static final String DESKTOP_ACTIVITY =
            DesktopHostComponents.EXTERNAL_HOME_CLASS;

    private int mDisplayId = Display.INVALID_DISPLAY;

    synchronized void configure(final int displayId) {
        mDisplayId = displayId > Display.DEFAULT_DISPLAY
                ? displayId : Display.INVALID_DISPLAY;
    }

    @Override
    public synchronized boolean onActivityStarting(
            final Intent intent,
            final String packageName) {
        final ComponentName component = intent == null
                ? null : intent.getComponent();
        if (intent == null || !shouldBlock(
                mDisplayId,
                intent.getAction(),
                intent.getCategories(),
                component == null ? null : component.getPackageName(),
                component == null ? null : component.getClassName(),
                intent.getPackage())) {
            return true;
        }
        Log.i(TAG, "blocked competing secondary Home start display="
                + mDisplayId + " package=" + packageName);
        return false;
    }

    static boolean shouldBlock(
            final int displayId,
            final String action,
            final Set<String> categories,
            final String componentPackage,
            final String componentClass,
            final String requestedPackage) {
        if (displayId <= Display.DEFAULT_DISPLAY
                || !Intent.ACTION_MAIN.equals(action)
                || categories == null
                || !categories.contains(Intent.CATEGORY_SECONDARY_HOME)) {
            return false;
        }
        return !(MAGICDESK_PACKAGE.equals(componentPackage)
                        && DESKTOP_ACTIVITY.equals(componentClass))
                && !(componentPackage == null
                        && MAGICDESK_PACKAGE.equals(requestedPackage));
    }
}
