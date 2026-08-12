package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

/** Dedicated component used to host the MagicDesk desktop on one display. */
public final class DesktopActivity extends DesktopShellActivity {
    private static final String TAG = "MagicDesk";
    private static final int WINDOWING_MODE_FULLSCREEN = 1;

    static Intent createLaunchIntent(final Context context) {
        return new Intent(context, DesktopActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static void launchOnDisplay(final Activity source, final int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY) {
            launchNow(source, displayId);
            return;
        }
        LocalDesktopNavigationController.acquire((generation, success, message) -> {
            if (!LocalDesktopNavigationController.isCurrentGeneration(
                    generation)) {
                return;
            }
            if (source.isFinishing() || source.isDestroyed()) {
                if (DesktopRuntimeBridge.getActiveDesktopDisplayId()
                        != Display.DEFAULT_DISPLAY) {
                    LocalDesktopNavigationController.releaseIfCurrent(
                            generation, null);
                }
                return;
            }
            if (!success) {
                CompatibilityDiagnostics.record(
                        "NUBIA-HOME-005",
                        "Could not protect the phone launcher before"
                                + " starting a local desktop",
                        message);
                Toast.makeText(
                        source,
                        source.getString(
                                R.string.status_local_desktop_guard_failed,
                                message),
                        Toast.LENGTH_LONG).show();
                return;
            }
            try {
                launchNow(source, displayId);
            } catch (RuntimeException error) {
                Log.w(TAG, "local desktop launch failed", error);
                LocalDesktopNavigationController.releaseIfCurrent(
                        generation, null);
                CompatibilityDiagnostics.record(
                        "DESKTOP-LAUNCH-001",
                        "Could not launch the local desktop",
                        error.getMessage(),
                        error);
                Toast.makeText(
                        source,
                        source.getString(
                                R.string.status_launch_failed,
                                error.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void launchNow(
            final Activity source, final int displayId) {
        if (DesktopRuntimeBridge.focusDesktopOnDisplay(displayId)) {
            return;
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        DesktopShellActivity.setLaunchWindowingMode(
                options, WINDOWING_MODE_FULLSCREEN);
        source.startActivity(
                createLaunchIntent(source).addFlags(
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        .putExtra(EXTRA_EXPECTED_DISPLAY_ID, displayId),
                options.toBundle());
    }
}
