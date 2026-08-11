package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;

/** Runs the production simulated-display lifecycle as a device regression. */
public final class DesktopLifecycleInstrumentation extends Instrumentation {
    @Override
    public void onCreate(final Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        runLifecycle();
    }

    private void runLifecycle() {
        final Bundle output = new Bundle();
        waitForShellAccess();
        final DesktopSelfTestResult result =
                DesktopSelfTestController.run(getTargetContext());
        output.putString("summary", result.summary());
        output.putString("report", result.format());
        finish(result.hasFailures()
                        ? Activity.RESULT_CANCELED : Activity.RESULT_OK,
                output);
    }

    private static void waitForShellAccess() {
        final long deadline = SystemClock.uptimeMillis() + 5_000L;
        do {
            if (ShellAccess.refresh().isReady()) {
                return;
            }
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
    }
}
