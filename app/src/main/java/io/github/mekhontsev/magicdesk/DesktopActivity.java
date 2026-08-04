package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.view.Display;

/** Dedicated component used to host the MagicDesk desktop on one display. */
public final class DesktopActivity extends DesktopShellActivity {
    private static final int WINDOWING_MODE_FREEFORM = 5;

    static Intent createLaunchIntent(final Context context) {
        return new Intent(context, DesktopActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static void launchOnDisplay(final Activity source, final int displayId) {
        final Display sourceDisplay = source.getDisplay();
        final int sourceDisplayId = sourceDisplay == null
                ? Display.DEFAULT_DISPLAY : sourceDisplay.getDisplayId();
        if (sourceDisplayId == displayId) {
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            DesktopShellActivity.setLaunchWindowingMode(
                    options, WINDOWING_MODE_FREEFORM);
            source.startActivity(
                    new Intent(source, DesktopActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    options.toBundle());
            return;
        }
        if (DesktopRuntimeBridge.focusDesktopOnDisplay(displayId)) {
            return;
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        DesktopShellActivity.setLaunchWindowingMode(
                options, WINDOWING_MODE_FREEFORM);
        source.startActivity(
                createLaunchIntent(source).addFlags(
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                options.toBundle());
    }
}
