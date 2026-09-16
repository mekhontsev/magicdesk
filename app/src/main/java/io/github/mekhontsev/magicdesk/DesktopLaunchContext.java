package io.github.mekhontsev.magicdesk;

import android.app.Activity;

/** Host operations required by the launch coordinator. */
interface DesktopLaunchContext {
    Activity activity();

    ToolLaunchTarget destination();

    default String destinationUniqueId() { return null; }

    void hideTransientUi();

    boolean launchAndroid(
            DesktopLaunchRequest request,
            Runnable onPrepared,
            DesktopActivityLaunchResult.Completion completion);

    void launchConsole(DesktopLaunchRequest request);

    boolean isUnavailable();

    void onStarted(DesktopLaunchRequest request);

    void onCompleted(DesktopLaunchRequest request);

    void onUnavailable(DesktopLaunchRequest request);

    void onFailure(DesktopLaunchRequest request, Throwable error);
}
