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

    static Intent createDesktopHostIntent(final Context context) {
        // The session selects both the component and the HOME root explicitly
        // through ActivityOptions. This is not a system secondary-HOME request.
        return createLaunchIntent(context)
                .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }
}
