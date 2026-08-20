package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Optional application integration that contributes a companion command. */
interface DesktopLaunchIntegration {
    boolean matches(AppLaunchTarget target);

    boolean isAvailable(Context context);

    DesktopExecSpec defaultExec(Context context);

    DesktopExecSpec prepareExec(
            Context context, DesktopExecSpec exec);
}
