package io.github.mekhontsev.magicdesk;

import android.app.Activity;

/** Host operations required by the launch coordinator. */
interface DesktopLaunchContext {
    Activity activity();

    int displayId();

    void hideTransientUi();

    boolean launchAndroid(
            DesktopLaunchRequest request, Runnable onPrepared);

    void launchConsole(DesktopLaunchRequest request, String command);

    boolean isUnavailable();

    void onStarted(DesktopLaunchRequest request);

    void onCompleted(DesktopLaunchRequest request);

    void onUnavailable(DesktopLaunchRequest request);

    void onFailure(DesktopLaunchRequest request, Throwable error);
}
