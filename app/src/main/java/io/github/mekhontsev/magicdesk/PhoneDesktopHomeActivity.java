package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;

/** Dedicated primary HOME host for a desktop running on the phone display. */
public final class PhoneDesktopHomeActivity extends DesktopShellActivity {
    static Intent createLaunchIntent(final Context context) {
        return new Intent(context, PhoneDesktopHomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override
    DesktopHomeSurfaceRouter.Surface requiredHomeSurface() {
        return DesktopHomeSurfaceRouter.Surface.DESKTOP;
    }
}
