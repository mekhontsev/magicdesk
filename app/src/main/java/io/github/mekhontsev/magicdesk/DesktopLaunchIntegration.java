package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/** Optional application integration that contributes a companion command. */
interface DesktopLaunchIntegration {
    boolean matches(AppLaunchTarget target);

    boolean isAvailable(Context context);

    DesktopExecSpec defaultExec(Context context);

    DesktopExecSpec prepareExec(
            Context context, DesktopExecSpec exec);

    default List<DesktopLaunchIntegrationAction> actions(
            final Context context) {
        return Collections.emptyList();
    }

    default void invokeAction(
            final Context context,
            final String actionId,
            final ActionCallback callback) {
        if (callback != null) {
            callback.onComplete(false, "unsupported integration action");
        }
    }

    interface ActionCallback {
        void onComplete(boolean success, String message);
    }
}
