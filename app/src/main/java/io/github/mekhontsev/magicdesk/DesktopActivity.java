package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;

/** Dedicated component used to create MagicDesk's HOME task on the Console display. */
public final class DesktopActivity extends DesktopShellActivity {
    static Intent createLaunchIntent(final Context context) {
        return new Intent(context, DesktopActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static void launchOnDisplay(final Activity source, final int displayId) {
        if (DesktopRuntimeBridge.focusDesktopOnDisplay(displayId)) {
            return;
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        DesktopShellActivity.invokeIntOption(
                options, "setLaunchWindowingMode", 1);
        source.startActivity(
                createLaunchIntent(source).addFlags(
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                options.toBundle());
    }
}
