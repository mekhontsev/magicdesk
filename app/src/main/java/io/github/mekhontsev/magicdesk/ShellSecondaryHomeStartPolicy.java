package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.util.Log;
import android.view.Display;

import java.util.Set;

/** Keeps unaddressed secondary-HOME selection out of a leased desktop workspace. */
final class ShellSecondaryHomeStartPolicy implements ShellActivityStartController.Listener {
    private volatile int mDisplayId = Display.INVALID_DISPLAY;

    void configure(final int displayId) {
        mDisplayId = displayId;
    }

    @Override
    public boolean onActivityStarting(final Intent intent, final String packageName) {
        final int displayId = mDisplayId;
        if (intent == null || !shouldBlock(displayId, intent.getAction(),
                intent.getCategories(), intent.getPackage())) {
            return true;
        }
        Log.i("MagicDeskTasks", "blocked unaddressed secondary Home start while hosting display="
                + displayId + " package=" + packageName);
        return false;
    }

    static boolean shouldBlock(final int displayId, final String action,
            final Set<String> categories, final String requestedPackage) {
        // The resolved component is not evidence of an addressed request: an
        // implicit SystemUI selector also resolves to our preferred HOME. The
        // controller receives no ActivityOptions, so it cannot repair its display.
        // Android's own per-area HOME starts carry a package; our explicit host
        // launches use ActivityOptions without the SECONDARY_HOME category.
        return displayId > Display.DEFAULT_DISPLAY
                && Intent.ACTION_MAIN.equals(action)
                && categories != null
                && categories.contains(Intent.CATEGORY_SECONDARY_HOME)
                && (requestedPackage == null || requestedPackage.isEmpty());
    }
}
