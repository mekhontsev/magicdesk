package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;

/** Stable primary HOME identity; local residency selects Start or Desktop content. */
public final class PhoneHomeActivity extends DesktopShellActivity {
    static final String EXTRA_SHOW_RECENT =
            BuildConfig.APPLICATION_ID + ".extra.SHOW_PHONE_RECENT";
    static Intent createLaunchIntent(final Context context) {
        return new Intent(context, PhoneHomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
}
