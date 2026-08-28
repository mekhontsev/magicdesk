package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dedicated component used to host the MagicDesk desktop on one display. */
public final class DesktopActivity extends DesktopShellActivity {
    private static final String TAG = "MagicDesk";
    private static final ExecutorService LAUNCH_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskDesktopLaunch");
                thread.setDaemon(true);
                return thread;
            });

    static Intent createLaunchIntent(final Context context) {
        return new Intent(context, DesktopActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static void launch(
            final Activity source,
            final DesktopDisplayTarget target) {
        launch(source, target, DesktopSessionPolicy.USER);
    }

    static void launch(
            final Activity source,
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        if (target == null
                || target.kind != DesktopDisplayTarget.Kind.PHONE
                || target.displayId != Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("phone display target is required");
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
                        "PHONE-HOME-005",
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
            launchNow(source, target, policy, generation);
        });
    }

    private static void launchNow(
            final Activity source,
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy,
            final long generation) {
        final int displayId = target.displayId;
        final DesktopSessionPolicy resolvedPolicy = policy == null
                ? DesktopSessionPolicy.USER : policy;
        DesktopRuntimeBridge.noteDesktopTarget(target, resolvedPolicy);
        if (DesktopRuntimeBridge.focusDesktopOnDisplay(displayId)) {
            return;
        }
        final Intent intent = createLaunchIntent(source).addFlags(
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(EXTRA_EXPECTED_DISPLAY_ID, displayId)
                .putExtra(EXTRA_PROFILE_DISPLAY_ID, target.profileDisplayId)
                .putExtra(EXTRA_PROFILE_KEY, target.profileKey)
                .putExtra(EXTRA_TARGET_KIND, target.kind.name())
                .putExtra(
                        EXTRA_ACTIVATION_SOURCE,
                        target.activationSource.name())
                .putExtra(EXTRA_SESSION_POLICY, resolvedPolicy.name());
        LAUNCH_EXECUTOR.execute(() -> {
            try {
                ShellAccess.launchDesktopHost(
                        displayId,
                        intent,
                        DesktopDisplayDrivers.forTarget(target)
                                .features().taskAreaPolicy);
            } catch (IOException | RuntimeException error) {
                source.runOnUiThread(() -> reportLaunchFailure(
                        source, target, generation, error));
            }
        });
    }

    private static void reportLaunchFailure(
            final Activity source,
            final DesktopDisplayTarget target,
            final long generation,
            final Throwable error) {
        Log.w(TAG, "local desktop launch failed", error);
        DesktopRuntimeBridge.clearDesktopTarget(target);
        LocalDesktopNavigationController.releaseIfCurrent(
                generation, null);
        final String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        CompatibilityDiagnostics.record(
                "DESKTOP-LAUNCH-001",
                "Could not launch the local desktop",
                message,
                error);
        if (!source.isFinishing() && !source.isDestroyed()) {
            Toast.makeText(
                    source,
                    source.getString(R.string.status_launch_failed, message),
                    Toast.LENGTH_LONG).show();
        }
    }
}
