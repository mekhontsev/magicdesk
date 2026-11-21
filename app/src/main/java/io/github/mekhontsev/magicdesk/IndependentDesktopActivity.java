package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;

/** Desktop host that does not participate in Android HOME resolution. */
public final class IndependentDesktopActivity extends DesktopShellActivity {
    static Intent createLaunchIntent(final Context context) {
        return new Intent(context, IndependentDesktopActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static Intent createDesktopHostIntent(final Context context) {
        return createLaunchIntent(context).addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }
}
