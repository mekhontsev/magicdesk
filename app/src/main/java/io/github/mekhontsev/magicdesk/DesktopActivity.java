package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;

/** Dedicated component used to host the MagicDesk desktop on one display. */
public final class DesktopActivity extends DesktopShellActivity {
    static Intent createLaunchIntent(final Context context) {
        return new Intent(context, DesktopActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static Intent createSecondaryHomeIntent(final Context context) {
        return createLaunchIntent(context)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_SECONDARY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }
}
